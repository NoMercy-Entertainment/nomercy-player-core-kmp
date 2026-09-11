// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

/**
 * Two `<audio>` elements, swapped rather than promoted.
 *
 * The browser twin of [tv.nomercy.player.core.ports.AudioBackend]'s other
 * implementations, and it is built the same way as the Android one for the same
 * reason: a crossfade needs both tracks audible at once, and one media element
 * plays one thing. When a fade finishes the element that faded in becomes the
 * current one, so nothing restarts at the moment the listener is meant to hear
 * a seamless join.
 *
 * Delegation is written out rather than declared with `by`, because the target
 * changes. `by` binds once at construction and would keep answering from the
 * element that just faded out.
 */
@Suppress("TooManyFunctions")
public class Html5AudioBackend internal constructor(
    private val make: () -> Html5MediaBackend,
) : AudioBackend {

    public constructor() : this({ Html5MediaBackend() })

    private var current: Html5MediaBackend = make()
    private var standby: Html5MediaBackend? = null

    private val crossfader = EqualPowerCrossfader()

    // The listeners a caller registered follow the swap. Without this, a chrome
    // that subscribed before the first transition stops hearing anything the
    // moment one happens, which looks like a player that died mid-queue.
    private val subscriptions: MutableList<Pair<String, (Any?) -> Unit>> = mutableListOf()

    override suspend fun load(url: String, opts: LoadOptions): Unit = current.load(url, opts)

    override suspend fun play(): Unit = current.play()

    override fun pause(): Unit = current.pause()

    override fun stop(): Unit = current.stop()

    // Both elements. A retired one is kept as the standby for the next
    // crossfade, so releasing only `current` would leave a live element, and the
    // connection it holds open, behind every torn-down player.
    override fun release() {
        current.release()
        standby?.release()
        standby = null
    }

    override fun currentTime(): Double = current.currentTime()

    override fun currentTime(seconds: Double): Unit = current.currentTime(seconds)

    override fun duration(): Double = current.duration()

    override fun volume(): Float = current.volume()

    override fun volume(value: Float): Unit = current.volume(value)

    override fun mute(): Unit = current.mute()

    override fun unmute(): Unit = current.unmute()

    override fun buffered(): Double = current.buffered()

    override fun bufferedRanges(): List<TimeRange> = current.bufferedRanges()

    override fun seekableRanges(): List<TimeRange> = current.seekableRanges()

    override fun playbackRate(): Double = current.playbackRate()

    override fun playbackRate(rate: Double): Unit = current.playbackRate(rate)

    override fun bandwidthEstimate(): Int = current.bandwidthEstimate()

    override fun state(): BackendState = current.state()

    override fun on(event: String, fn: (Any?) -> Unit) {
        subscriptions += event to fn
        current.on(event, fn)
    }

    override fun off(event: String, fn: (Any?) -> Unit) {
        subscriptions.removeAll { it.first == event && it.second == fn }
        current.off(event, fn)
    }

    // A browser will decode a second stream. Whether the tab has been allowed to
    // make sound at all is a different question, and it surfaces when play()
    // rejects rather than being predictable here.
    override fun supportsCrossfade(): Boolean = true

    override suspend fun loadSecondary(url: String) {
        val next: Html5MediaBackend = standby ?: make().also { standby = it }
        next.volume(0f)
        next.load(url)
    }

    // Decoding the first samples before the fade starts is what stops a
    // crossfade beginning with silence: an element asked to play and fade in on
    // the same tick spends the first hundred milliseconds buffering.
    override suspend fun primeSecondary(seekMs: Long) {
        val next: Html5MediaBackend = standby ?: return
        if (seekMs > 0) next.currentTime(seekMs / MILLIS_PER_SECOND)
        next.play()
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        val incoming: Html5MediaBackend = standby ?: return
        val outgoing: Html5MediaBackend = current
        val startVolume: Float = outgoing.volume()

        crossfader.run(
            outgoing = ElementGain(outgoing),
            incoming = ElementGain(incoming),
            fade = EqualPowerCrossfader.Fade(startVolume, durationMs, curve),
        )

        swapTo(incoming, retiring = outgoing)
    }

    // The faded-in element becomes the current one and the faded-out one becomes
    // the standby, reused for the next transition rather than released. A
    // release per track is an element built and dropped per track, and each one
    // takes a fresh connection to the media server.
    private fun swapTo(incoming: Html5MediaBackend, retiring: Html5MediaBackend) {
        subscriptions.forEach { (event, fn) ->
            retiring.off(event, fn)
            incoming.on(event, fn)
        }
        retiring.stop()
        current = incoming
        standby = retiring
    }

    override fun disposeSecondary() {
        val next: Html5MediaBackend = standby ?: return
        standby = null
        next.release()
    }

    override fun secondaryGain(): Float = standby?.volume() ?: 0f

    override fun secondaryGain(value: Float) {
        standby?.volume(value)
    }

    /**
     * Mounts nothing and needs to mount nothing.
     *
     * An `<audio>` element plays without ever being in the document, which is
     * why this engine has no counterpart to the video backend's surface. Autoplay
     * policy is the one thing that does need the page: a browser refuses sound
     * until the visitor has interacted with it, and `play()` rejects. The player
     * above hears that as a failed play rather than as a silent success.
     */
    private class ElementGain(private val backend: Html5MediaBackend) : GainSink {
        override fun gain(): Float = backend.volume()

        override fun gain(value: Float): Unit = backend.volume(value)

        override suspend fun play(): Unit = backend.play()

        // Deliberately nothing: this backend reuses the retired element as the
        // next standby, so tearing it down here would mean building a fresh one
        // for every track.
        override fun releaseAfterFade() {}
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1000.0
    }
}
