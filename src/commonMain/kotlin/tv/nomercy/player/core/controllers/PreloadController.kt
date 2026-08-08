// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PreloadCompletePayload
import tv.nomercy.player.core.events.PreloadErrorPayload
import tv.nomercy.player.core.events.PreloadProgressPayload
import tv.nomercy.player.core.events.PreloadStartPayload
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.events.TransitionCompletePayload
import tv.nomercy.player.core.events.TransitionProgressPayload
import tv.nomercy.player.core.events.TransitionStartPayload
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.PreloadAsset
import tv.nomercy.player.core.ports.PreloadContext
import tv.nomercy.player.core.ports.PreloadStrategy
import tv.nomercy.player.core.ports.TransitionBackend
import tv.nomercy.player.core.ports.TransitionContext
import tv.nomercy.player.core.ports.TransitionStrategy

// The reference drives a crossfade on requestAnimationFrame. Native core has no
// frame callback — the engines have one and core does not talk to their render
// threads — so the same cadence comes off a timer.
private const val FRAME_INTERVAL_MS = 16L

private const val CURSOR_CHANGED = "cursor-changed"

// Asking the two strategies whether it is time yet, and doing what they say.
//
// Both were constructed, configured from PlayerConfig and exposed by getters,
// and neither was ever consulted: shouldPreload and shouldTransition had no
// callers at all. So every track and episode change was a hard cut into a load
// that started when the previous item ended.
//
// One listener per question, matching the reference. `item` resets the cycle
// and cancels whatever the outgoing item had in flight; `time` asks the two
// strategies, and each fires at most once per item.
// Preload, transition and the crossfade seam are one lifecycle: what is loaded
// ahead, when it is swapped, and what happens in between. They share the state
// that makes them correct together.
@Suppress("TooManyFunctions")
public class PreloadController(
    private val ctx: PlayerContext,
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val preload: () -> PreloadStrategy,
    private val transition: () -> TransitionStrategy,
    // How an asset is warmed. Null where the host wired no HTTP transport, which
    // is the ordinary case for a backend that fetches its own segments — the
    // strategy still gets to name what it wanted, and the events still fire.
    private val warm: (suspend (PreloadAsset) -> Unit)? = null,
) {

    private val subscriptions: MutableList<Subscription> = mutableListOf()

    private var preloadFired: Boolean = false
    private var transitionFired: Boolean = false
    private var crossfadeEnabled: Boolean = false
    private var leadSeconds: Double = 0.0
    private var tailSeconds: Double = 0.0

    // Bumped whenever the cursor moves. Every piece of in-flight work captured
    // the value it started under and exits silently when it no longer matches —
    // a fetch that lands after the viewer skipped is describing yesterday's item.
    private var epoch: Long = 0L

    private var fade: Job? = null

    public fun setup(config: PlayerConfig) {
        crossfadeEnabled = config.crossfadeEnabled
        leadSeconds = config.crossfadeLeadSeconds
        tailSeconds = config.crossfadeTailSeconds

        subscriptions += ctx.on(CoreEvents.Item) { resetCycle() }
        subscriptions += ctx.on(CoreEvents.Time) { update -> orchestrate(update.time) }
    }

    public fun dispose() {
        for (subscription in subscriptions) subscription.dispose()
        subscriptions.clear()
        cancelFade()
    }

    private fun resetCycle() {
        preloadFired = false
        transitionFired = false
        preload().cancel()
        transition().cancel(CURSOR_CHANGED)
        epoch += 1
        cancelFade()
    }

    private fun orchestrate(position: Double) {
        // Asked with a null next item rather than skipped, because a custom
        // strategy is entitled to see the end of the queue coming.
        val nextItem: PlaylistItem? = queue.peekNext()
        val context = PreloadContext(
            currentTime = position,
            duration = ctx.internalDuration,
            nextItem = nextItem,
        )

        maybePreload(context)
        maybeTransition(context)
    }

    // The strategy is asked even at the end of the queue, where nextItem is
    // null: a custom one is entitled to see the queue running out. It just has
    // nothing to start on.
    /**
     * Warm [nextItem] now, rather than when the playhead reaches the
     * threshold.
     *
     * The machinery was here and only a timer could reach it, so a host that
     * knew what was coming — a radio UI the moment a station is picked, a
     * queue built ahead of time — had no way to say so and had to wait for the
     * lead-seconds window like everyone else.
     *
     * Marks the cycle fired, so the automatic pass does not repeat the work a
     * moment later.
     */
    public suspend fun preloadNow(nextItem: PlaylistItem) {
        preloadFired = true
        runPreload(nextItem, preload())
    }

    private fun maybePreload(context: PreloadContext) {
        if (preloadFired) return
        if (!preload().shouldPreload(context)) return
        val nextItem: PlaylistItem = context.nextItem ?: return

        preloadFired = true
        scope.launch { runPreload(nextItem, preload()) }
    }

    private fun maybeTransition(context: PreloadContext) {
        if (!crossfadeEnabled || transitionFired) return
        if (!transition().shouldTransition(context)) return

        val outgoing: PlaylistItem? = queue.item()
        val nextItem: PlaylistItem? = context.nextItem
        if (outgoing == null || nextItem == null) return

        transitionFired = true
        runTransition(outgoing, nextItem)
    }

    // Warms what the strategy named and says how far it got.
    //
    // An empty list completes immediately rather than staying silent: core's own
    // DefaultPreloadStrategy names nothing, because what is worth prefetching
    // differs between a video manifest and an album cover, and a consumer
    // listening for preloadComplete should not have to special-case that.
    private suspend fun runPreload(nextItem: PlaylistItem, strategy: PreloadStrategy) {
        val capturedEpoch: Long = epoch
        val assets: List<PreloadAsset> = strategy.assetsToPreload(nextItem)

        ctx.emit(CoreEvents.PreloadStart, PreloadStartPayload(nextItem, assets))

        if (assets.isEmpty()) {
            ctx.emit(CoreEvents.PreloadComplete, PreloadCompletePayload(nextItem))
            return
        }

        if (warmAll(assets, nextItem, capturedEpoch)) {
            ctx.emit(CoreEvents.PreloadComplete, PreloadCompletePayload(nextItem))
        }
    }

    // True when every asset landed under the epoch it started in. A cursor move
    // mid-run leaves silently: its result describes yesterday's item.
    private suspend fun warmAll(
        assets: List<PreloadAsset>,
        nextItem: PlaylistItem,
        capturedEpoch: Long,
    ): Boolean {
        var loaded = 0
        for (asset in assets) {
            if (epoch != capturedEpoch) return false
            if (!warmOne(asset, nextItem, capturedEpoch)) return false

            loaded += 1
            ctx.emit(
                CoreEvents.PreloadProgress,
                PreloadProgressPayload(nextItem, loaded.toDouble(), assets.size.toDouble()),
            )
        }
        return epoch == capturedEpoch
    }

    // False when the run should stop. A warm that throws is reported once and
    // abandons the rest: the usual cause is the whole host transport being gone,
    // and reporting it per asset would be one failure told five times.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun warmOne(asset: PreloadAsset, nextItem: PlaylistItem, capturedEpoch: Long): Boolean {
        val fetch: suspend (PreloadAsset) -> Unit = warm ?: return true
        return try {
            fetch(asset)
            true
        } catch (cause: Throwable) {
            if (epoch == capturedEpoch) {
                ctx.emit(CoreEvents.PreloadError, PreloadErrorPayload(nextItem, cause.message))
            }
            false
        }
    }

    // Rides the strategy across the overlap window and reports every frame.
    //
    // The fraction is progress through `crossfadeLeadSeconds + crossfadeTailSeconds`
    // measured from the point the lead begins, which is what the strategy's gain
    // curve is written against.
    private fun runTransition(outgoing: PlaylistItem, incoming: PlaylistItem) {
        val strategy: TransitionStrategy = transition()
        val backend: TransitionBackend? = ctx.backend as? TransitionBackend

        strategy.start(outgoing, incoming, backend)
        ctx.emit(CoreEvents.TransitionStart, TransitionStartPayload(outgoing, incoming))

        val capturedEpoch: Long = epoch
        fade = scope.launch {
            var running = true
            while (isActive && running) {
                running = stillFading(capturedEpoch) && !tickFade(strategy, backend, outgoing, incoming)
                if (running) delay(FRAME_INTERVAL_MS)
            }
        }
    }

    // A cursor move or a teardown mid-fade ends it. Left unchecked the loop
    // ticks a strategy against an item nothing is playing any more.
    private fun stillFading(capturedEpoch: Long): Boolean {
        if (epoch != capturedEpoch) return false
        return ctx.phase != PlayerPhase.DISPOSING && ctx.phase != PlayerPhase.DISPOSED
    }

    // True once the window has closed.
    private fun tickFade(
        strategy: TransitionStrategy,
        backend: TransitionBackend?,
        outgoing: PlaylistItem,
        incoming: PlaylistItem,
    ): Boolean {
        val fraction: Double = fractionNow()
        strategy.tick(
            TransitionContext(
                currentTime = ctx.internalCurrentTime,
                duration = ctx.internalDuration,
                outgoingItem = outgoing,
                incomingItem = incoming,
                fraction = fraction,
            ),
            backend,
        )
        ctx.emit(CoreEvents.TransitionProgress, TransitionProgressPayload(outgoing, incoming, fraction))

        if (fraction < 1.0) return false

        strategy.complete(outgoing, incoming)
        ctx.emit(CoreEvents.TransitionComplete, TransitionCompletePayload(outgoing, incoming))
        fade = null
        return true
    }

    private fun fractionNow(): Double {
        val window: Double = leadSeconds + tailSeconds
        if (window <= 0.0) return 1.0

        val elapsed: Double = ctx.internalCurrentTime - (ctx.internalDuration - leadSeconds)
        return (elapsed / window).coerceIn(0.0, 1.0)
    }

    private fun cancelFade() {
        fade?.cancel()
        fade = null
    }
}
