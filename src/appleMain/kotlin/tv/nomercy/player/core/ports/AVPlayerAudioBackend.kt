// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Two AVPlayers, swapped rather than promoted.
//
// The same shape as the Android and desktop audio backends, deliberately. The
// fade is shared; what each engine contributes is how to hold two handles and
// move a gain. Three different shapes would mean three places to look when a
// transition misbehaves on one platform, which is how a crossfade ends up
// subtly different on each.
//
// AVFoundation mixes two players without being asked, as long as the app's audio
// session allows it — which is the app's decision and not this library's, the
// same way the session category is.
public class AVPlayerAudioBackend : AudioBackend {

    // The equaliser and spectrum, shared by both players.
    //
    // One graph rather than one per player, unlike Android. The tap attaches to
    // an item's audio mix rather than to the engine, so a crossfade does not
    // move it — both players' items carry a mix built from this same graph, and
    // the filter state that matters is per item anyway because each mix has its
    // own tap instance underneath.
    private val dsp = AppleDspGraph()

    override fun audioGraph(): AudioDspGraph = dsp

    private var current: AVPlayerVideoBackend = AVPlayerVideoBackend()
    private var standby: AVPlayerVideoBackend? = null

    private val crossfader = EqualPowerCrossfader()

    // Listeners follow the swap, or a chrome that subscribed once goes silent
    // at the first transition and reads as a player that died mid-queue.
    private val subscriptions: MutableList<Pair<String, (Any?) -> Unit>> = mutableListOf()

    override suspend fun load(url: String, opts: LoadOptions): Unit = current.load(url, opts)

    override suspend fun play(): Unit = current.play()

    override fun pause(): Unit = current.pause()

    override fun stop(): Unit = current.stop()

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

    // Whether two players are actually audible together depends on the audio
    // session the app configured, which the library does not own and must not
    // claim to know. AVFoundation itself imposes no limit.
    override fun supportsCrossfade(): Boolean = true

    override suspend fun loadSecondary(url: String) {
        val next: AVPlayerVideoBackend = standby ?: AVPlayerVideoBackend().also { standby = it }
        next.volume(0f)
        next.load(url)
    }

    // An AVPlayerItem built from an unloaded asset is never ready and play()
    // silently does nothing, so the second track is loaded and positioned before
    // the fade rather than at the moment it starts.
    override suspend fun primeSecondary(seekMs: Long) {
        val next: AVPlayerVideoBackend = standby ?: return
        if (seekMs > 0) next.currentTime(seekMs / MILLIS_PER_SECOND)
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        val incoming: AVPlayerVideoBackend = standby ?: return
        val outgoing: AVPlayerVideoBackend = current
        val startVolume: Float = outgoing.volume()

        crossfader.run(
            outgoing = BackendGain(outgoing),
            incoming = BackendGain(incoming),
            fade = EqualPowerCrossfader.Fade(startVolume, durationMs, curve),
        )

        subscriptions.forEach { (event, fn) ->
            outgoing.off(event, fn)
            incoming.on(event, fn)
        }
        outgoing.stop()
        current = incoming
        standby = outgoing
    }

    override fun disposeSecondary() {
        val next: AVPlayerVideoBackend = standby ?: return
        standby = null
        next.release()
    }

    override fun secondaryGain(): Float = standby?.volume() ?: 0f

    override fun secondaryGain(value: Float) {
        standby?.volume(value)
    }

    // Both engines. Without it a caller can dispose the standby and stop the
    // current one and still leave two AVPlayers holding their items.
    override fun release() {
        standby?.release()
        standby = null
        current.release()
    }

    // The release the fader asks for is a no-op here for the same reason as on
    // the other two: the retired engine becomes the next standby, and tearing it
    // down would mean building a fresh one per track.
    private class BackendGain(private val backend: AVPlayerVideoBackend) : GainSink {
        override fun gain(): Float = backend.volume()

        override fun gain(value: Float): Unit = backend.volume(value)

        override suspend fun play(): Unit = backend.play()

        override fun releaseAfterFade(): Unit = Unit
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
