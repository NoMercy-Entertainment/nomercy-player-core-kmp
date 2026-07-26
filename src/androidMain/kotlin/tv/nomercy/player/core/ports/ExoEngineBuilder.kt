// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator

// How the engine is actually configured, as opposed to merely constructed.
//
// ExoPlayer.Builder(context).build() compiles, runs, and plays a file, which is
// why this was the whole of it for a while. What a default engine does not do
// is survive a 40Mbit remux on a television with a 256MB heap, keep the audio
// bitstream intact on the way to an AVR, or hand HDR frames to a display
// pipeline that can show them. All of that is configuration, and none of it was
// carried across from the shipped app.
//
// Split out of the backend rather than inlined there because the backend is
// already the largest class in the module and this is a separate concern: one
// decides what the engine is, the other decides what to say about it.
// Everything is set on the builder, and nothing is set on the player
// afterwards. That is not a style preference: Media3 verifies the calling
// thread on every setter, and a backend can be constructed from anywhere — an
// instrumentation runner, a background scope, a DI graph. Configuring after
// build() threw "Player is accessed on the wrong thread" from thirteen device
// tests the first time this was written that way.
//
// The looper is named for the same reason. Left to itself the builder binds to
// whatever looper happens to be current, so an engine built off the main thread
// would answer a different thread than every callback arrives on.
internal fun buildEngine(context: Context): ExoPlayer {
    val budget: BufferConfig = bufferConfigForDevice(context)

    return ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setLoadControl(loadControlFor(budget))
        // Hold a network wakelock while playing. Without it a TV that dims its
        // screen can let the radio idle mid-stream, and the rebuffer that
        // follows looks like the server stopped sending.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        // Prepare the next item only when it is nearly due. The alternative
        // holds two decoders open, which on a low-heap device is the difference
        // between a gapless transition and an OOM.
        .setUseLazyPreparation(true)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        // handleAudioFocus, so something else starting to play takes the sound
        // rather than both being audible at once. A media library that ignored
        // focus would talk over every notification.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .build()
}

// The allocator is primed to the whole budget up front.
//
// Media3 grows it on demand otherwise, and growing it during playback is the
// allocation that lands while a decoder is already holding memory — which on
// the devices this budget exists for is exactly when there is none left.
private fun loadControlFor(budget: BufferConfig): DefaultLoadControl {
    val allocator = DefaultAllocator(true, ALLOCATION_CHUNK_BYTES)
    allocator.setTargetBufferSize(budget.targetBufferBytes)

    return DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            budget.minBufferMs,
            budget.maxBufferMs,
            budget.bufferForPlaybackMs,
            budget.bufferForPlaybackAfterRebufferMs,
        )
        // Keep what has already played, so a seek backwards is instant rather
        // than a re-fetch of something the device still had a moment ago.
        .setBackBuffer(budget.backBufferMs, budget.retainBackBufferFromKeyframe)
        .setTargetBufferBytes(budget.targetBufferBytes)
        // Time over size. A high-bitrate stream hits the byte ceiling long
        // before it has buffered enough seconds to ride out a hiccup, and the
        // result is a player that rebuffers on a connection that is keeping up.
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}

// Media3's own default. Named because the number appearing bare next to a
// budget measured in megabytes reads as if the two were related.
private const val ALLOCATION_CHUNK_BYTES = 64 * 1024
