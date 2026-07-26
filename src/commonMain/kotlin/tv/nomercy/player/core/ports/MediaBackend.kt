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

    // How far ahead of the playhead the engine has data, in seconds. What a
    // scrubber draws its buffered bar from.
    public fun buffered(): Double

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

    public fun state(): BackendState

    public fun on(event: String, fn: (Any?) -> Unit)
    public fun off(event: String, fn: (Any?) -> Unit)
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
