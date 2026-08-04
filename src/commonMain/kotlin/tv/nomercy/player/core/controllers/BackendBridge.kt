// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.errors.ErrorCode
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.BackendErrorPayload
import tv.nomercy.player.core.events.BackendLoadedPayload
import tv.nomercy.player.core.events.BackendLoadingPayload
import tv.nomercy.player.core.events.BackendStalledPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.events.StreamError
import tv.nomercy.player.core.events.ProgressPayload
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.player.ActionSource
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.MediaBackend
import tv.nomercy.player.core.ports.defaultClock
import kotlinx.coroutines.CoroutineScope

private const val PERCENT = 100.0

// What the web calls an element error it cannot map to anything more specific,
// and already a key in DEFAULT_RETRY_POLICY here — where it is not retried,
// because an engine that could not decode a source will not decode it twice.
private const val UNCLASSIFIED_ENGINE_FAILURE = "media/decode-fatal-all"

private const val UNKNOWN_ENGINE = "unknown"

// What the engine says, turned into what the player says.
//
// The controllers drive the engine; nothing was listening to it come back. That
// is the difference between `play` and `playing`: one is the action, the other
// is the engine confirming it actually started, and only the engine knows when.
// Without this bridge a chrome bound to `playing` shows a spinner forever while
// audio comes out of the speakers.
//
// Found by running the shared conformance scenarios against the native player:
// transport/play observed [beforePlay, phase, play] and stopped there.
public class BackendBridge(
    private val ctx: PlayerContext,
    // Both nullable-by-default so a fixture can build a bridge without either.
    // Without a scope there is nothing to schedule a retry on, and the bridge
    // then behaves exactly as it did before recovery existed: every failure is
    // the last word.
    private val scope: CoroutineScope? = null,
    private val clock: Clock = defaultClock(),
) {
    private val recovery: FailureRecovery = FailureRecovery(ctx, scope, clock)


    private val handlers: MutableList<Pair<String, (Any?) -> Unit>> = mutableListOf()

    private var announcedFirstFrame: Boolean = false

    /** The connection returned. Handed on to recovery, which owns the reload. */
    public fun onNetworkRestored() {
        recovery.networkRestored()
    }

    public fun attach(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.LOADED_METADATA) {
            announceLoaded(backend)
            val duration: Double = backend.duration()
            if (duration > 0.0) {
                ctx.internalDuration = duration
                ctx.emit(CoreEvents.Duration, duration)
            }
        }

        listen(backend, CanonicalBackendEvent.PLAYING) {
            ctx.playState = PlayState.PLAYING
            ctx.bufferState = BufferState.IDLE
            if (ctx.phase == PlayerPhase.STARTING) ctx.transitionPhase(PlayerPhase.PLAYING)
            ctx.emit(CoreEvents.Playing, Unit)

            // Once per item, after the first frame is actually on screen. A
            // chrome waiting to take its poster down needs the frame to exist
            // first, or it swaps a still image for a black one.
            if (!announcedFirstFrame) {
                announcedFirstFrame = true
                ctx.emit(CoreEvents.FirstFrame, Unit)
            }
        }

        // A stop the player did not ask for.
        //
        // Audio focus going to a call, headphones coming out, a media key the
        // engine handled itself. The field was written and nothing was told, so
        // every consumer went on showing playing: the chrome's button, the
        // notification mirroring it, and the other devices on the account.
        listen(backend, CanonicalBackendEvent.PAUSE) { announcePause() }

        // The other half of the same bridge, for a start the player did not ask
        // for — focus coming back, or that same media key.
        listen(backend, CanonicalBackendEvent.PLAY) { announcePlay() }

        attachStreamFailures(backend)

        listen(backend, CanonicalBackendEvent.LOAD_START) {
            ctx.bufferState = BufferState.LOADING
            // A new item has its own first frame.
            announcedFirstFrame = false
            // Nothing comes out while a source loads, so a state still saying
            // playing is a state that is lying. A toggle bound to it spent the
            // viewer's first press pausing a silent player.
            announcePause()
            ctx.emit(
                CoreEvents.BackendLoading,
                BackendLoadingPayload(url = ctx.queue.current()?.url.orEmpty(), kind = BACKEND_KIND),
            )
        }

        listen(backend, CanonicalBackendEvent.ENDED) {
            ctx.transitionPhase(PlayerPhase.ENDED)
            ctx.emit(CoreEvents.Ended, Unit)
        }

        attachPositionUpdates(backend)
        attachAdaptationTelemetry(backend)
    }

    // What the adaptive pipeline did, forwarded rather than interpreted.
    //
    // The payloads cross unchanged: an engine that knows a fragment's url and
    // duration knows them better than anything here could reconstruct, and a
    // bridge that rebuilt them would be a second, worse source of the same fact.
    // A malformed payload is dropped rather than guessed at — a fragment event
    // with no fragment tells a consumer nothing, and inventing an empty url
    // would put a lie in a support log.
    private fun attachAdaptationTelemetry(backend: MediaBackend) {
        forward(backend, CanonicalBackendEvent.MANIFEST_LOADED, CoreEvents.StreamManifestLoaded)
        forward(backend, CanonicalBackendEvent.FRAGMENT_LOADED, CoreEvents.StreamFragmentLoaded)
        forward(backend, CanonicalBackendEvent.LEVEL_CONSIDERED, CoreEvents.StreamLevelConsidered)
        forward(backend, CanonicalBackendEvent.LEVEL_SWITCHED, CoreEvents.StreamLevelSwitched)
        forward(backend, CanonicalBackendEvent.ADAPTIVE_LEVEL_SWITCHED, CoreEvents.LevelSwitched)
    }

    private inline fun <reified T : Any> forward(backend: MediaBackend, event: String, key: EventKey<T>) {
        listen(backend, event) { payload ->
            (payload as? T)?.let { ctx.emit(key, it) }
        }
    }

    private fun attachPositionUpdates(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.TIME_UPDATE) {
            // Between an advance and the incoming item's mount this engine is
            // still holding the outgoing one. Publishing its position here
            // hands every listener the previous item's position stamped with
            // the new item's identity.
            if (!ctx.mediaIsStale()) publishPosition(backend)
        }
    }

    private fun publishPosition(backend: MediaBackend) {
        val time: Double = backend.currentTime()
        val duration: Double = backend.duration()
        ctx.internalCurrentTime = time
        if (duration > 0.0) ctx.internalDuration = duration

        // A position that advances is the engine saying it is being fed, so
        // it is also the end of a stall.
        //
        // Clearing one on `canplay` alone is a rule borrowed from a media
        // element, which re-fires canplay after every stall. VlcjVideoBackend
        // announces it once per item on purpose — twice would make anything
        // counting loads count two — and libVLC re-announces `playing` only
        // on a state change, so on the desktop neither event that could clear
        // a stall ever arrives again. Measured against the real engine: a
        // stall at the first HLS rendition switch left bufferState STALLED for
        // the remaining eighty seconds of a film playing at full rate.
        if (ctx.bufferState == BufferState.STALLED) ctx.bufferState = BufferState.IDLE

        val percentage: Double = if (duration <= 0.0) 0.0 else time / duration * PERCENT

        // Only time. progress is derived from it and throttled to
        // progressIntervalMs by TimeController, the same as the reference —
        // emitting it here put a save-the-watch-position handler on the
        // engine's own tick rate, several writes a second per viewer.
        ctx.emit(CoreEvents.Time, TimeUpdate(time = time, duration = duration, percentage = percentage))
    }

    // Every subscription is remembered so detaching a backend really detaches
    // it. Swapping engines mid-session otherwise leaves the old one's listeners
    // emitting into a player that has moved on.
    public fun detach(backend: MediaBackend) {
        // Over a copy. off() is the backend's code and not ours, and an engine
        // that unregisters a sibling handler while being asked to drop one would
        // otherwise take the whole detach down with a concurrent modification.
        for ((event, handler) in handlers.toList()) backend.off(event, handler)
        handlers.clear()
    }

    // Hungry, and why.
    //
    // The same engine event means two different things to a viewer depending on
    // when it arrives. Before the first frame the source is still loading and a
    // spinner is expected; after playback started the buffer ran dry, and that
    // is worth saying out loud rather than showing the same spinner and hoping.
    private fun attachBufferState(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.WAITING) {
            ctx.bufferState = if (announcedFirstFrame) BufferState.STALLED else BufferState.LOADING
            ctx.emit(CoreEvents.BackendWaiting, Unit)
        }

        listen(backend, CanonicalBackendEvent.STALLED) {
            ctx.bufferState = BufferState.STALLED
            ctx.emit(CoreEvents.BackendStalled, BackendStalledPayload(ctx.backend?.currentTime() ?: 0.0))
        }

        // Announced, like every other handler here. Setting the field alone
        // left the clear invisible: nothing recomputes the snapshot for a value
        // nobody emits, so a player that finished buffering while paused went on
        // reporting LOADING until some unrelated event moved the state.
        listen(backend, CanonicalBackendEvent.CAN_PLAY) {
            ctx.bufferState = BufferState.IDLE
            // The stream is healthy, so the retries it took to get here are spent
            // budget rather than a running total. A film watched through three bad
            // patches would otherwise die at the third having survived the first
            // two, which is the failure mode that looks least like a bug.
            recovery.progressed()
        }
    }

    // The engine's stream failures, in the stream vocabulary rather than as a
    // generic error. A chrome that wants to say "the connection dropped" rather
    // than "playback failed" needs the distinction, and it is the engine that
    // knows which one happened.
    //
    // Fatal by default: an engine that reports a stream error and keeps playing
    // is reporting a recovered one, and it does not report at all when nothing
    // went wrong. Treating them as recoverable would have a chrome swallow the
    // case where playback actually stopped.
    private fun attachStreamFailures(backend: MediaBackend) {
        attachBufferState(backend)

        listen(backend, CanonicalBackendEvent.STREAM_ERROR) {
            ctx.emit(CoreEvents.StreamError, StreamError(details = it?.toString() ?: "", fatal = true))
        }

        listen(backend, CanonicalBackendEvent.ERROR) { announceFailure(backend, it) }
    }

    // The engine's own failure, which was the one name in the vocabulary nothing
    // listened for.
    //
    // Nine emit sites across the three engines announced into nothing: a decode
    // that failed, a source AVFoundation refused, libVLC giving up — and the
    // HDR-unplayable refusal, which exists to stop playback and tell the viewer
    // why, and was doing neither.
    //
    // Both events, because they answer different questions. backend:error carries
    // what the engine said and which engine said it, for a support log; error is
    // the one surface a consumer subscribes to in order to know something went
    // wrong, and it should not have to enumerate every specific failure first.
    private fun announceFailure(backend: MediaBackend, reported: Any?) {
        val engineWord: String? = reported?.toString()?.takeIf { it.isNotBlank() }

        // Only a code that is one. Media3 reports its own name for a failure and
        // the other two report nothing at all; passing either through as a code
        // would put an identifier in a dashboard that no dashboard groups.
        val code: String = engineWord
            ?.takeIf { ErrorCode.parseOrNull(it) != null }
            ?: UNCLASSIFIED_ENGINE_FAILURE

        // The reference derives this from the engine's state, where an engine in
        // error is not an engine that is buffering. It is stored here, so without
        // this a spinner keeps turning on top of an item that already failed.
        ctx.bufferState = BufferState.IDLE

        // Tried before it is announced, because a failure being recovered from is
        // not a failure a viewer needs to read about. The web swallows the same
        // ones — a fragment that did not arrive is retried three times before
        // anything reaches the page — and this port announced every one of them
        // as fatal, so a phone that walked past a wall ended the film.
        if (recovery.tryRecover(engineWord)) return

        val kind: String = backend::class.simpleName ?: UNKNOWN_ENGINE
        ctx.emit(CoreEvents.BackendError, BackendErrorPayload(error = engineWord, kind = kind))
        ctx.emit(
            CoreEvents.Error,
            PlayerErrorEvent(
                code = code,
                message = engineWord ?: "the engine reported a failure and did not say which",
                severity = Severity.ERROR,
                scope = ErrorScope.backend(kind),
                context = mapOf("engine" to kind, "reported" to engineWord),
            ),
        )
    }

    // Only when it was not already going. An engine repeats itself and a chrome
    // that redraws on every repeat flickers for nothing.
    private fun announcePlay() {
        if (ctx.playState == PlayState.PLAYING) return

        ctx.playState = PlayState.PLAYING
        ctx.emit(CoreEvents.Play, PlaySource(ActionSource.PLATFORM))
    }

    // Only when it was going, and only when it is not just the item finishing.
    //
    // Every engine stops its clock at the end, so a pause always rides along with
    // the ending; announcing that one would have a chrome show a paused player for
    // the instant before it shows a finished one. The web player suppresses it the
    // same way, reading the element's own ended flag.
    private fun announcePause() {
        if (ctx.playState != PlayState.PLAYING) return
        if (ctx.phase == PlayerPhase.ENDED) return

        ctx.playState = PlayState.PAUSED
        ctx.emit(CoreEvents.Pause, PlaySource(ActionSource.PLATFORM))
    }

    // The engine's own channel, spoken rather than merely declared. A consumer
    // subscribing to backend:loaded heard nothing, ever, while the registry said
    // the event existed - which reads as a working feature.
    private fun announceLoaded(backend: MediaBackend) {
        ctx.emit(
            CoreEvents.BackendLoaded,
            BackendLoadedPayload(
                url = ctx.queue.current()?.url.orEmpty(),
                kind = BACKEND_KIND,
                duration = backend.duration(),
            ),
        )
    }

    private fun listen(backend: MediaBackend, event: String, handler: (Any?) -> Unit) {
        backend.on(event, handler)
        handlers.add(event to handler)
    }
}

// What the web writes into these two payloads for a plain media element. The
// desktop and mobile engines are all "media" from a consumer's point of view;
// a bridge that named the engine would make the same stream read differently
// on three platforms.
private const val BACKEND_KIND = "media"
