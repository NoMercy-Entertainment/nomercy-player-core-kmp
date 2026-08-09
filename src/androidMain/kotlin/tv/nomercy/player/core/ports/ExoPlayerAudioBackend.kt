// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import kotlinx.coroutines.CoroutineScope

// Two engines, swapped rather than promoted.
//
// A crossfade needs both tracks audible at once and an ExoPlayer plays one, so
// there are two — and when a fade finishes, the one that faded in simply becomes
// the current one. Moving media between engines instead would restart playback
// at the exact moment the listener is meant to hear a seamless join.
//
// Delegation is written out rather than declared with `by`, because the target
// changes: `by` binds once at construction and would keep answering from the
// engine that just faded out. Every call below reads `current` at the moment it
// is made, which is the whole mechanism.
public class ExoPlayerAudioBackend(
    private val context: android.content.Context,
    private val scope: CoroutineScope? = null,
) : AudioBackend {

    // One processor per engine, because a biquad carries history and two
    // streams sharing one filter would smear the outgoing track into the
    // incoming one for the length of a crossfade.
    private val currentEqualiser = BiquadEqAudioProcessor()
    private var standbyEqualiser: BiquadEqAudioProcessor? = null

    private var current: ExoPlayerVideoBackend = ExoPlayerVideoBackend(context, scope, currentEqualiser)
    private var standby: ExoPlayerVideoBackend? = null

    private val crossfader = EqualPowerCrossfader()

    // The listeners a caller registered follow the swap. Without this, a chrome
    // that subscribed before the first transition stops hearing anything the
    // moment one happens — which looks like a player that died mid-queue.
    private val subscriptions: MutableList<Pair<String, (Any?) -> Unit>> = mutableListOf()

    // The curve, applied to both engines and kept across a transition.
    //
    // A crossfade swaps which engine is audible. An equaliser bound to one of
    // them would go flat halfway through every track change and come back on
    // the next — heard as the tone shifting under the music, which is the last
    // thing anyone would look for in a queue.
    //
    // Settings are fanned out; filter state is not. Both engines get the same
    // numbers and each keeps its own history.
    private val equaliserGraph: AudioDspGraph = FannedGraph()

    override fun audioGraph(): AudioDspGraph = equaliserGraph

    private inner class FannedGraph : AudioDspGraph {
        private fun each(action: (AudioDspGraph) -> Unit) {
            action(currentEqualiser.graph())
            standbyEqualiser?.let { action(it.graph()) }
        }

        override fun setEqBands(bands: List<EqBand>): Unit = each { it.setEqBands(bands) }

        override fun bandGain(frequencyHz: Int, gainDb: Double): Unit = each { it.bandGain(frequencyHz, gainDb) }

        override fun preGain(linear: Double): Unit = each { it.preGain(linear) }

        override fun eqEnabled(enabled: Boolean): Unit = each { it.eqEnabled(enabled) }

        // Only the audible engine, because two taps would deliver two frames
        // per window and a visualiser would draw the outgoing track over the
        // incoming one for the length of a crossfade.
        override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription =
            currentEqualiser.graph().installFrameTap(onFrame)

        override fun removeFrameTap(): Unit = currentEqualiser.graph().removeFrameTap()
    }

    override suspend fun load(url: String, opts: LoadOptions): Unit = current.load(url, opts)

    override suspend fun play(): Unit = current.play()

    override fun pause(): Unit = current.pause()

    override fun stop(): Unit = current.stop()

    // Both engines, not just the one playing. This backend keeps a retired
    // engine as the standby for the next crossfade, so releasing only `current`
    // would leave a whole ExoPlayer — and the codec it holds — behind every
    // time a player is torn down.
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

    override fun playbackRate(): Double = current.playbackRate()

    override fun playbackRate(rate: Double): Unit = current.playbackRate(rate)

    override fun state(): BackendState = current.state()

    override fun on(event: String, fn: (Any?) -> Unit) {
        subscriptions += event to fn
        current.on(event, fn)
    }

    override fun off(event: String, fn: (Any?) -> Unit) {
        subscriptions.removeAll { it.first == event && it.second == fn }
        current.off(event, fn)
    }

    // Media3 can always decode a second stream. Whether the device has a codec
    // free for it is not something the engine can predict — it surfaces when
    // preparing fails, which is a better answer than a guess here.
    override fun supportsCrossfade(): Boolean = true

    override suspend fun loadSecondary(url: String) {
        val next: ExoPlayerVideoBackend = standby ?: ExoPlayerVideoBackend(
            context,
            scope,
            BiquadEqAudioProcessor().also { standbyEqualiser = it },
        ).also {
            standby = it
        }
        next.volume(0f)
        next.load(url)
    }

    // Decoding the first frames before the fade starts is what stops a
    // crossfade beginning with silence: an engine asked to play and fade in the
    // same instant spends the first hundred milliseconds buffering.
    override suspend fun primeSecondary(seekMs: Long) {
        val next: ExoPlayerVideoBackend = standby ?: return
        if (seekMs > 0) next.currentTime(seekMs / MILLIS_PER_SECOND)
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        val incoming: ExoPlayerVideoBackend = standby ?: return
        val outgoing: ExoPlayerVideoBackend = current
        val startVolume: Float = outgoing.volume()

        crossfader.run(
            outgoing = BackendGain(outgoing),
            incoming = BackendGain(incoming),
            fade = EqualPowerCrossfader.Fade(startVolume, durationMs, curve),
        )

        swapTo(incoming, retiring = outgoing)
    }

    // The faded-in engine becomes the current one and the faded-out one becomes
    // the standby, reused for the next transition rather than released. A
    // release per track is a codec acquired and dropped per track, which on a
    // long queue is the thing that eventually fails.
    private fun swapTo(incoming: ExoPlayerVideoBackend, retiring: ExoPlayerVideoBackend) {
        subscriptions.forEach { (event, fn) ->
            retiring.off(event, fn)
            incoming.on(event, fn)
        }
        retiring.stop()
        current = incoming
        standby = retiring
    }

    override fun disposeSecondary() {
        val next: ExoPlayerVideoBackend = standby ?: return
        standby = null
        next.release()
    }

    override fun secondaryGain(): Float = standby?.volume() ?: 0f

    override fun secondaryGain(value: Float) {
        standby?.volume(value)
    }

    // Both handles, and the release the fader asks for is deliberately a no-op:
    // this backend reuses the retired engine as the next standby, so tearing it
    // down here would mean building a fresh one for every track.
    private class BackendGain(private val backend: ExoPlayerVideoBackend) : GainSink {
        override fun gain(): Float = backend.volume()

        override fun gain(value: Float): Unit = backend.volume(value)

        override suspend fun play(): Unit = backend.play()

        override fun releaseAfterFade(): Unit = Unit
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
