// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

private const val MILLIS_PER_SECOND = 1000.0
private const val FULL_VOLUME_PERCENT = 100

// The desktop engine, over libVLC.
//
// libVLC decodes practically everything, which is what a desktop client needs
// when the file came off a disc rip and nothing else will touch it. It also
// reports its state in its own vocabulary, on its own thread, so this class is
// two things: a MediaBackend, and a translation of VLC's event stream into the
// canonical one every controller above is written against.
//
// Headless by default: no video surface is attached, so it decodes and reports
// without needing a window. A desktop client attaches its own surface through
// the embedded player; the conformance gate does not, which is what lets the
// gate run anywhere libVLC is installed.
public class VlcjVideoBackend(
    private val factory: MediaPlayerFactory = MediaPlayerFactory(),
) : MediaBackend {

    private val bus = StringEventBus()
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    // VLC reports position in milliseconds and this contract is in seconds. One
    // conversion, here, rather than at every call site.
    private var lastKnownDuration: Double = 0.0

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.LOADED_METADATA)
                bus.emit(CanonicalBackendEvent.CAN_PLAY)
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.PLAYING)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.PAUSE)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.ENDED)
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                bus.emit(CanonicalBackendEvent.TIME_UPDATE, newTime / MILLIS_PER_SECOND)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                lastKnownDuration = newLength / MILLIS_PER_SECOND
                bus.emit(CanonicalBackendEvent.LOADED_METADATA)
            }

            // VLC calls this "buffering" and reports a percentage. Zero means it
            // has run dry, which is the moment a chrome should say so; anything
            // else is progress and not worth an event.
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (newCache <= 0f) bus.emit(CanonicalBackendEvent.WAITING)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.ERROR)
            }
        })
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        // prepare rather than play: loading and starting are separate decisions
        // above, and an engine that started on its own would ignore a refused
        // beforePlay.
        player.media().prepare(url)
    }

    override suspend fun play() {
        bus.emit(CanonicalBackendEvent.PLAY)
        player.controls().play()
    }

    override fun pause() {
        player.controls().setPause(true)
    }

    override fun stop() {
        player.controls().stop()
    }

    // libVLC answers -1 for "I do not know yet", on time as well as on
    // length. A negative position is meaningless to everything above and a
    // scrubber renders it as a bar pointing the wrong way.
    override fun currentTime(): Double {
        val reported: Long = player.status().time()
        return if (reported > 0) reported / MILLIS_PER_SECOND else 0.0
    }

    override fun currentTime(seconds: Double) {
        player.controls().setTime((seconds * MILLIS_PER_SECOND).toLong())
    }

    // VLC's own length is -1 until it has read the container, so the last value
    // it reported is the honest answer rather than a negative number a scrubber
    // would try to divide by.
    override fun duration(): Double {
        val reported: Long = player.status().length()
        return if (reported > 0) reported / MILLIS_PER_SECOND else lastKnownDuration
    }

    override fun volume(): Float = player.audio().volume() / FULL_VOLUME_PERCENT.toFloat()

    override fun volume(value: Float) {
        player.audio().setVolume((value * FULL_VOLUME_PERCENT).toInt())
    }

    override fun mute() {
        player.audio().isMute = true
    }

    override fun unmute() {
        player.audio().isMute = false
    }

    // libVLC exposes cache fullness as a percentage rather than a buffered
    // range, so the honest answer is how far ahead of the playhead it claims to
    // be, and zero when it will not say.
    override fun buffered(): Double = currentTime()

    override fun playbackRate(): Double = player.status().rate().toDouble()

    override fun playbackRate(rate: Double) {
        player.controls().setRate(rate.toFloat())
    }

    override fun state(): BackendState = when {
        player.status().isPlaying -> BackendState.PLAYING
        player.status().isPlayable -> BackendState.READY
        else -> BackendState.IDLE
    }

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    // Releasing is not optional: libVLC holds native resources and a player that
    // is dropped without this leaks a decoder thread per item.
    public fun release() {
        player.release()
        factory.release()
    }

    public companion object {
        // True when libVLC is present and binds. A desktop build that assumed it
        // would fail at the first play with a linker error instead of a message
        // anyone can act on.
        public fun isAvailable(): Boolean = whyUnavailable() == null

        // The reason, so a desktop client can say "install VLC" rather than
        // "playback failed". Null when it binds.
        public fun whyUnavailable(): String? = try {
            MediaPlayerFactory().release()
            null
        } catch (missing: UnsatisfiedLinkError) {
            "libVLC is not installed or is the wrong architecture: ${missing.message}"
        } catch (missing: NoClassDefFoundError) {
            "the VLCJ binding is not on the classpath: ${missing.message}"
        }
    }
}
