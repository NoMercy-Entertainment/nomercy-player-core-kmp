// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The engine that actually plays something. ExoPlayer, AVPlayer, VLCJ — one
// interface over all of them, and the adaptive-bitrate path runs through here
// rather than through StreamSource.
//
// Events are raw strings, deliberately: the vocabulary is the web media
// element's, verbatim, so every engine maps its native stream onto one ordered
// spine that all three ecosystems already speak. See BackendEvents.
//
// Wide because a media engine is wide. Splitting it would let an implementation
// claim to be a backend while being unable to report its own duration.
@Suppress("ComplexInterface", "TooManyFunctions")
public interface MediaBackend {
    public suspend fun load(url: String, opts: LoadOptions = LoadOptions())
    public suspend fun play()
    public fun pause()
    public fun stop()

    // Seconds, matching the contract and the media element. The bare-noun
    // getter/setter pair is the contract's shape.
    public fun currentTime(): Double
    public fun currentTime(seconds: Double)
    public fun duration(): Double

    // 0..1, unlike the player's 0..100: this is the engine's own scale and
    // converting at the boundary is the controller's job, not every engine's.
    public fun volume(): Float
    public fun volume(value: Float)
    public fun mute()
    public fun unmute()

    // The absolute timeline position, in seconds, that buffered data reaches.
    //
    // The same frame of reference currentTime() and duration() use, which is
    // what lets a scrubber draw its buffered bar as buffered() / duration().
    // NOT how far ahead of the playhead the data goes: the doc said that for a
    // while and no implementation ever agreed with it, so a reader had two
    // contradictory sources and picked whichever they read first.
    //
    // Contiguous from the playhead, and the default is the walk that makes it
    // so. An engine that reports ranges gets the frontier right by reporting
    // them: the walk is written once rather than per engine, which is how the
    // Apple backend came to answer 3600 for a list the other two read as 5.
    //
    // Overridden by an engine that reports a frontier of its own and no ranges
    // — Media3 and libVLC both do — because the default would read their empty
    // range list as nothing buffered.
    public fun buffered(): Double = bufferedFrontier(bufferedRanges(), currentTime())

    // Where data actually is, and where the playhead may go.
    //
    // Both default to empty, which means "this engine cannot say" rather than
    // "there is nothing". Only some can: AVFoundation reports both directly,
    // Media3 and libVLC report a single frontier and no holes. The player fills
    // in what it can from what it does know rather than making every engine
    // implement a shape it has no data for.
    //
    // Ranges rather than one number because a seek backwards into evicted
    // buffer is the case a single frontier cannot describe: it says data
    // reaches to 400s while the first 200 have been dropped, and a scrubber
    // drawn from it promises a jump that will stall.
    public fun bufferedRanges(): List<TimeRange> = emptyList()

    public fun seekableRanges(): List<TimeRange> = emptyList()

    public fun playbackRate(): Double
    public fun playbackRate(rate: Double)

    /**
     * What this engine currently measures the connection at, in bits per second.
     *
     * An engine streaming segments knows what they cost, and the reference reads
     * it: `bandwidth()` prefers a consumer-set estimator and falls through to
     * here. This port declared no such thing, so the fall-through had nowhere to
     * land and `bandwidth()` returned 0 for every caller who had not injected an
     * estimator of their own — which is the same wrong answer the web comment
     * says the two-field split used to give.
     *
     * Zero is the honest default for an engine that does not measure. Guessing
     * would put an adaptation decision on a measurement that never happened.
     */
    public fun bandwidthEstimate(): Int = 0

    public fun state(): BackendState

    public fun on(event: String, fn: (Any?) -> Unit)
    public fun off(event: String, fn: (Any?) -> Unit)

    /**
     * Gives back everything the engine holds. The instance is finished afterwards.
     *
     * Every backend already had this and no interface named it, which meant an
     * engine taken from the registry could be created through the seam and not
     * freed through it — the caller held a `VideoBackend` and the method was on
     * the concrete class. What an engine holds is a decoder's frame pool, its
     * demuxer buffers and a native handle, none of which the garbage collector
     * can see, so the cost of not calling it does not show up as pressure. It
     * shows up as a process that grows by a few hundred megabytes per film.
     *
     * [stop] is not this. Stopping ends playback and leaves the engine ready for
     * the next item, which is what an item change wants; this ends the engine.
     */
    public fun release()

    // Somewhere else the operating system can send this engine's output, if this
    // engine has such a route at all.
    //
    // Null by default, and that is an honest answer rather than a gap: most
    // engines have no route sender, and a consumer asking one for AirPlay should
    // be told there is none rather than handed a port that accepts a picker
    // request and shows nothing. Only the engine that owns the native player
    // instance the platform can route can answer this — AVPlayer on Apple today.
    public fun externalPlayback(): ExternalPlayback? = null
}

// The event names every backend emits, whatever engine is underneath.
//
// Taken from the web media element verbatim rather than invented, because the
// conformance runners compare native and web event sequences against each
// other and a renamed event would read as a behavioural difference.
public object BackendEvents {
    public const val LOAD_START: String = "loadstart"
    public const val LOADED_METADATA: String = "loadedmetadata"
    public const val CAN_PLAY: String = "canplay"
    public const val PLAY: String = "play"
    public const val PLAYING: String = "playing"
    public const val TIME_UPDATE: String = "timeupdate"
    public const val WAITING: String = "waiting"
    public const val PAUSE: String = "pause"
    public const val ENDED: String = "ended"
    public const val STREAM_ERROR: String = "stream:error"
}
