// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Two libmpv handles and a fade between them.
 *
 * The same shape as [VlcjAudioBackend] and as the Android backend, deliberately:
 * the fade itself is shared, and what an engine contributes is only how to hold
 * two handles and move a gain. Two shapes would mean two places to look when a
 * transition misbehaves on one platform.
 *
 * No shared instance to hold, which is the one real difference from libVLC. An
 * mpv handle owns its own configuration and there is no plugin cache to pay for
 * twice, so the standby is a second handle rather than a second player made
 * from a shared engine — and releasing one cannot pull the ground out from
 * under the other.
 */
public class MpvAudioBackend : AudioBackend {

    private var current: MpvVideoBackend = MpvVideoBackend()
    private var standby: MpvVideoBackend? = null

    private val crossfader = EqualPowerCrossfader()

    // Listeners follow the swap. Without this a chrome that subscribed once
    // stops hearing anything after the first transition.
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

    // A desktop plays as many streams as it has audio for, which is not the
    // constraint it is on a phone.
    override fun supportsCrossfade(): Boolean = true

    // What the playing engine is at, captured before the standby is touched.
    //
    // mpv's volume IS per handle, unlike libVLC's, so this is not strictly
    // required here — it is kept because the fade reads it either way and
    // because a transition that behaves differently on two desktops for a
    // reason nobody wrote down is the expensive kind of difference.
    private var playingGain: Float? = null

    override suspend fun loadSecondary(url: String) {
        playingGain = current.volume()
        val next: MpvVideoBackend = standby ?: MpvVideoBackend().also { standby = it }
        next.volume(0f)
        next.load(url)
    }

    // Silent, paused and decoded. A fade that begins with a track still opening
    // begins with silence, and mpv reports no position on media it has not
    // started.
    override suspend fun primeSecondary(seekMs: Long) {
        val next: MpvVideoBackend = standby ?: return
        if (seekMs > 0) next.currentTime(seekMs / MILLIS_PER_SECOND)
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        val incoming: MpvVideoBackend = standby ?: return
        val outgoing: MpvVideoBackend = current
        val startVolume: Float = playingGain ?: outgoing.volume()

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
        playingGain = null
    }

    override fun disposeSecondary() {
        val next: MpvVideoBackend = standby ?: return
        standby = null
        next.release()
    }

    override fun secondaryGain(): Float = standby?.volume() ?: 0f

    override fun secondaryGain(value: Float) {
        standby?.volume(value)
    }

    // Both handles. A caller that releases the standby and stops the current one
    // still leaves a live mpv handle and its poll thread behind, which in one
    // process is a slow leak and in a test run is the previous test's audio
    // deciding the next one's result.
    public fun release() {
        standby?.release()
        standby = null
        current.release()
    }

    // The release the fader asks for is a no-op here for the same reason as on
    // Android and on libVLC: the retired engine becomes the next standby, and
    // tearing it down would mean building a fresh handle per track.
    private class BackendGain(private val backend: MpvVideoBackend) : GainSink {
        override fun gain(): Float = backend.volume()

        override fun gain(value: Float): Unit = backend.volume(value)

        override suspend fun play(): Unit = backend.play()

        override fun releaseAfterFade(): Unit = Unit
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
