// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.desktop

import tv.nomercy.player.core.ports.NowPlaying
import tv.nomercy.player.core.ports.SystemTransport
import tv.nomercy.player.core.ports.TransportActions
import tv.nomercy.player.core.ports.TransportPlaybackState

// What a Linux desktop shows in its own media controls.
//
// There is no single Linux media API; there is a published D-Bus interface that
// every desktop environment agreed to read. So this exports a well-known name
// and answers questions, rather than calling anything.
//
// The bus is the only part that needs Linux. Everything above it is the mapping,
// and MPRIS has enough sharp edges in that mapping to be worth testing: lengths
// and positions are microseconds rather than milliseconds, artwork is a URI the
// desktop will try to open itself, and the status strings are matched verbatim.
public class LinuxMprisTransport(private val bus: MprisBus) : SystemTransport {

    override fun setNowPlaying(nowPlaying: NowPlaying) {
        bus.publishMetadata(mprisMetadataOf(nowPlaying))
    }

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        bus.publishStatus(mprisStatusOf(state))
        bus.publishPosition(microsecondsOf(positionMs))
    }

    override fun setActionHandlers(actions: TransportActions) {
        bus.publishCanControl(
            buildSet {
                if (actions.onPlay != null) add(MprisMethod.PLAY)
                if (actions.onPause != null) add(MprisMethod.PAUSE)
                if (actions.onStop != null) add(MprisMethod.STOP)
                if (actions.onNext != null) add(MprisMethod.NEXT)
                if (actions.onPrevious != null) add(MprisMethod.PREVIOUS)
                if (actions.onSeekTo != null) add(MprisMethod.SEEK)
            },
        )
        bus.onMethodCall { method, argument -> dispatch(method, argument, actions) }
    }

    override fun clear() {
        bus.publishMetadata(emptyMap())
        bus.publishStatus(MPRIS_STOPPED)
    }

    override fun release() {
        bus.onMethodCall(null)
        bus.unexport()
    }

    private fun dispatch(method: MprisMethod, argumentMicroseconds: Long, actions: TransportActions) {
        when (method) {
            MprisMethod.PLAY -> actions.onPlay?.invoke()
            MprisMethod.PAUSE -> actions.onPause?.invoke()
            MprisMethod.STOP -> actions.onStop?.invoke()
            MprisMethod.NEXT -> actions.onNext?.invoke()
            MprisMethod.PREVIOUS -> actions.onPrevious?.invoke()
            // Both carry a position and the desktop sends microseconds. A client
            // that passed the number through unconverted would seek a thousand
            // times too far and land at the end of every track.
            MprisMethod.SET_POSITION -> actions.onSeekTo?.invoke(millisecondsOf(argumentMicroseconds))
            MprisMethod.SEEK -> actions.onSeekTo?.invoke(millisecondsOf(argumentMicroseconds))
        }
    }
}

public enum class MprisMethod { PLAY, PAUSE, STOP, NEXT, PREVIOUS, SEEK, SET_POSITION }

// The three strings the specification defines. Matched verbatim by every desktop
// that reads this, so they are values rather than an enum's name.
public const val MPRIS_PLAYING: String = "Playing"
public const val MPRIS_PAUSED: String = "Paused"
public const val MPRIS_STOPPED: String = "Stopped"

public fun mprisStatusOf(state: TransportPlaybackState): String = when (state) {
    TransportPlaybackState.PLAYING -> MPRIS_PLAYING
    TransportPlaybackState.PAUSED -> MPRIS_PAUSED
    TransportPlaybackState.STOPPED -> MPRIS_STOPPED
}

// MPRIS counts in microseconds. Everything else in this library counts in
// milliseconds, and the two being a thousand apart is exactly the kind of
// difference that produces a track which appears to last seventeen minutes.
public fun microsecondsOf(milliseconds: Long): Long = milliseconds * MICROSECONDS_PER_MILLISECOND

public fun millisecondsOf(microseconds: Long): Long = microseconds / MICROSECONDS_PER_MILLISECOND

// The metadata map, with the keys the specification names.
//
// A field with nothing in it is left out rather than published empty, because a
// desktop draws the key it was given: an empty artist is a blank line under the
// title where no line should be.
public fun mprisMetadataOf(nowPlaying: NowPlaying): Map<String, Any> {
    val metadata: MutableMap<String, Any> = linkedMapOf("xesam:title" to nowPlaying.title)

    // A list, because the specification says so. A desktop reading a bare string
    // here shows nothing at all rather than failing, which is the worst way for
    // this to be wrong.
    nowPlaying.artist?.let { metadata["xesam:artist"] = listOf(it) }
    nowPlaying.album?.let { metadata["xesam:album"] = it }
    nowPlaying.artworkUrl?.let { metadata["mpris:artUrl"] = it }
    if (nowPlaying.durationMs > 0) metadata["mpris:length"] = microsecondsOf(nowPlaying.durationMs)

    return metadata
}

// The D-Bus surface, which is all that is left to write against Linux.
public interface MprisBus {

    public fun publishMetadata(metadata: Map<String, Any>)

    public fun publishStatus(status: String)

    public fun publishPosition(microseconds: Long)

    // The set of what can be done rather than a flag each. Five booleans in a
    // row is the call site where two of them get swapped, and a desktop then
    // greys out the wrong control.
    public fun publishCanControl(methods: Set<MprisMethod>)

    public fun onMethodCall(handler: ((MprisMethod, Long) -> Unit)?)

    public fun unexport()
}

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
