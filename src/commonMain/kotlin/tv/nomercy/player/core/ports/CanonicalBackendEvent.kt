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

    // The length, once an engine knows it.
    //
    // A browser knows it by loadedmetadata, which is why the reference never
    // needed this and the port did not have it. libVLC does not: whichever
    // path announces readability first latches it, and when that path is not
    // lengthChanged the duration is still zero when the bridge reads it — so
    // `duration` never reached a consumer on desktop at all, measured absent
    // across a 97-second trace.
    //
    // Port vocabulary rather than contract surface: this is how an engine tells
    // the bridge something, and the contract event it produces is the one that
    // already existed.
    public const val DURATION_CHANGE: String = "durationchange"
    public const val WAITING: String = "waiting"

    // The pipeline being hungry and the network having stopped feeding it are
    // different things, and a chrome shows different things for the two.
    public const val STALLED: String = "stalled"
    public const val PAUSE: String = "pause"
    public const val ENDED: String = "ended"

    // The cues of the selected in-container track, as the engine crosses them.
    //
    // The web's IVideoBackend declares exactly this name and every video engine
    // here can answer it: Media3 through Player.Listener.onCues, AVFoundation
    // through AVPlayerItemLegibleOutput. It was missing, so a track the engine
    // was already decoding reached nothing — the renderer subscribed to
    // CoreEvents.SubtitleCue and no production code path ever emitted one.
    //
    // An empty payload is a real report rather than silence: it is how a
    // renderer is told to wipe what it is drawing when the cue ends, the track
    // is turned off, or a new source is loaded.
    public const val SUBTITLE_CUE: String = "subtitleCue"
    public const val ERROR: String = "error"
    public const val STREAM_ERROR: String = "stream:error"

    // What an adaptive pipeline reports about itself.
    //
    // Only the engines that own their own manifest parsing emit these — Media3
    // through its analytics listener, hls.js on the web. AVFoundation and libVLC
    // adapt behind a closed door and say nothing, which is why a consumer must
    // treat these as telemetry rather than as the way to learn the current
    // quality. quality() is that; these are how it got there.
    public const val MANIFEST_LOADED: String = "stream:manifest-loaded"
    public const val FRAGMENT_LOADED: String = "stream:fragment-loaded"
    public const val LEVEL_CONSIDERED: String = "stream:level-considered"
    public const val LEVEL_SWITCHED: String = "stream:level-switched"

    // The plain one, which is not the stream-scoped one above.
    //
    // A backend doing its own adaptive switching announces it under this name —
    // the web's IVideoBackend declares exactly this — and the contract carries
    // it as a base event. Only the stream-scoped variant was forwarded, so
    // CoreEvents.LevelSwitched was declared, documented, and never emitted by
    // anything. A chrome showing "Auto (720p)" had nothing to bind to.
    public const val ADAPTIVE_LEVEL_SWITCHED: String = "level-switched"

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
