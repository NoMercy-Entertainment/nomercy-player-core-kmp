// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.natives.libvlc.VlcInstance

// Two libVLC players, swapped rather than promoted.
//
// The same shape as the Android audio backend, and deliberately so: the fade
// itself is shared, and what each engine contributes is only how to hold two
// handles and move a gain. Writing the two backends to different shapes would
// mean two places to look when a transition misbehaves on one platform.
//
// One engine instance for both players. libVLC's instance owns the plugin cache
// and the logging, and two of them in a process is two copies of both — plus a
// second bind on a machine where finding the library is the slow part.
public class VlcjAudioBackend(
    private val instance: VlcInstance = VlcInstance(),
) : AudioBackend {

    private var current: VlcjVideoBackend = VlcjVideoBackend(instance)
    private var standby: VlcjVideoBackend? = null

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

    // libVLC plays as many streams as the machine has audio for, which on a
    // desktop is not the constraint it is on a phone.
    override fun supportsCrossfade(): Boolean = true

    // What the playing engine is at, captured here because here is the last
    // moment the answer is trustworthy.
    //
    // libVLC's volume is not strictly per-player: the line below silences the
    // standby, and the playing engine's own reading follows it down. Measured on
    // a real pair — the playing engine read 0.5, the standby was set to 0, and
    // half a second later the playing engine read 0 as well. A fade that asked
    // the outgoing engine what it was playing at once both were live faded IN to
    // silence, which is the whole transition inaudible.
    private var playingGain: Float? = null

    override suspend fun loadSecondary(url: String) {
        playingGain = current.volume()
        val next: VlcjVideoBackend = standby ?: VlcjVideoBackend(instance).also { standby = it }
        next.volume(0f)
        next.load(url)
    }

    // libVLC will not report a position on media it has not started, and a fade
    // that begins with a buffering track begins with silence. Starting it paused
    // is what gets the first frames decoded.
    override suspend fun primeSecondary(seekMs: Long) {
        val next: VlcjVideoBackend = standby ?: return
        if (seekMs > 0) next.currentTime(seekMs / MILLIS_PER_SECOND)
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        val incoming: VlcjVideoBackend = standby ?: return
        val outgoing: VlcjVideoBackend = current
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
        val next: VlcjVideoBackend = standby ?: return
        standby = null
        next.release()
    }

    override fun secondaryGain(): Float = standby?.volume() ?: 0f

    override fun secondaryGain(value: Float) {
        standby?.volume(value)
    }

    // Both engines and the instance that made them.
    //
    // Without it a caller can release the standby and stop the current one and
    // still leave two native players and a plugin cache alive — which in one
    // process is a slow leak and in a test run is the previous test's audio
    // state deciding the next one's result.
    public fun release() {
        standby?.release()
        standby = null
        current.release()
        instance.release()
    }

    // The release the fader asks for is a no-op here for the same reason as on
    // Android: the retired engine is the next standby, and tearing it down would
    // mean building a fresh one per track.
    private class BackendGain(private val backend: VlcjVideoBackend) : GainSink {
        override fun gain(): Float = backend.volume()

        override fun gain(value: Float): Unit = backend.volume(value)

        override suspend fun play(): Unit = backend.play()

        override fun releaseAfterFade(): Unit = Unit
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
