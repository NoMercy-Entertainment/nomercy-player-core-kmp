// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The names every engine reports in, whatever it calls them natively.
//
// These are the web backend's names verbatim. ExoPlayer, AVPlayer and VLCJ each
// have their own vocabulary and their own idea of what order things happen in;
// mapping all three onto one set of strings is what lets the controllers above
// them be written once. A native rename here would mean a controller that has
// to know which engine it is talking to, which is the thing this whole layer
// exists to prevent.
public object CanonicalBackendEvent {
    public const val LOAD_START: String = "loadstart"
    public const val LOADED_METADATA: String = "loadedmetadata"
    public const val CAN_PLAY: String = "canplay"
    public const val PLAY: String = "play"
    public const val PLAYING: String = "playing"
    public const val TIME_UPDATE: String = "timeupdate"
    public const val WAITING: String = "waiting"

    // The pipeline being hungry and the network having stopped feeding it are
    // different things, and a chrome shows different things for the two.
    public const val STALLED: String = "stalled"
    public const val PAUSE: String = "pause"
    public const val ENDED: String = "ended"
    public const val ERROR: String = "error"
    public const val STREAM_ERROR: String = "stream:error"

    // The relative order every engine must produce. Not everything an engine
    // emits — just the five points that have to happen in this sequence for the
    // controllers above to make sense. An engine that reports play before
    // loadstart is describing a different lifecycle.
    public val PLAY_PAUSE_SPINE: List<String> = listOf(LOAD_START, CAN_PLAY, PLAY, TIME_UPDATE, PAUSE)

    // What a well-behaved engine emits, recorded rather than required. The
    // difference between this and the spine is the honest answer to how far
    // three engines actually agree.
    public val FULL_ORDER: List<String> = listOf(
        LOAD_START,
        LOADED_METADATA,
        CAN_PLAY,
        PLAY,
        PLAYING,
        TIME_UPDATE,
        PAUSE,
        ENDED,
    )

    public val ALL: List<String> = listOf(
        LOAD_START,
        LOADED_METADATA,
        CAN_PLAY,
        PLAY,
        PLAYING,
        TIME_UPDATE,
        WAITING,
        STALLED,
        PAUSE,
        ENDED,
        ERROR,
        STREAM_ERROR,
    )
}
