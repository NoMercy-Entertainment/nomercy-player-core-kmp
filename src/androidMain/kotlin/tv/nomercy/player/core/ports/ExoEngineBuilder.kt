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
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import okhttp3.OkHttpClient

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
internal fun buildEngine(
    context: Context,
    auth: AuthHeaders,
    selector: DefaultTrackSelector,
    // Given only by the audio backend. Video has no equaliser today, and
    // inserting a processor into a video sink to change nothing would cost a
    // pass over every sample for no reason.
    processor: BiquadEqAudioProcessor? = null,
): ExoEngine {
    val budget: BufferConfig = bufferConfigForDevice(context)
    val renderers: AudioPassthroughRenderersFactory = if (processor == null) {
        AudioPassthroughRenderersFactory.create(context, budget.isTvDevice)
    } else {
        EqualiserRenderersFactory(context, budget.isTvDevice, processor)
    }

    selector.parameters = selector.buildUponParameters()
        // A rung change that is not seamless is still better than a stall. On
        // TV hardware the seamless path is often unavailable and refusing to
        // adapt at all is what a viewer sees as buffering.
        .setAllowVideoNonSeamlessAdaptiveness(true)
        // Adapt to the panel, not to the decoder's ambitions. Without this a
        // 4K ladder is climbed on a 1080p television, spending bandwidth on
        // pixels that are thrown away before they are shown.
        .setViewportSizeToPhysicalDisplaySize(context, true)
        // Never offer a stream this device cannot actually decode. Exceeding
        // capabilities turns a rung that would have been skipped into one that
        // starts and then fails partway through.
        .setExceedRendererCapabilitiesIfNecessary(false)
        .setExceedVideoConstraintsIfNecessary(false)
        .setAudioOffloadPreferences(offloadFor(renderers))
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setRenderersFactory(renderers)
        .setTrackSelector(selector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(context, auth), DefaultExtractorsFactory()))
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

    return ExoEngine(player = player, renderers = renderers)
}

// The renderers factory with the equaliser spliced into its sink.
//
// A subclass rather than a configuration, because Media3 builds its audio sink
// inside the renderers factory and the only way in is to override the method
// that makes it. Passthrough is left alone here: a device bitstreaming Atmos to
// a receiver is not decoding the audio at all, so there are no samples for an
// equaliser to filter and inserting one would silently defeat the passthrough
// this same factory exists to enable.
private class EqualiserRenderersFactory(
    context: Context,
    isTvForm: Boolean,
    private val processor: BiquadEqAudioProcessor,
) : AudioPassthroughRenderersFactory(context, isTvForm) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(processor))
        .build()
}

// ENABLED, never REQUIRED.
//
// Offload is what tells Media3 to hand compressed frames to the audio HAL
// rather than decoding them first, which is the half of passthrough the sink
// cannot do on its own. REQUIRED would refuse any track the device cannot
// offload — and a device that advertises surround still cannot offload every
// stream, so the strict form turns a working soundtrack into silence.
private fun offloadFor(renderers: AudioPassthroughRenderersFactory): AudioOffloadPreferences =
    if (!renderers.deviceSupportsSurround) {
        AudioOffloadPreferences.DEFAULT
    } else {
        AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            // Gapless needs the decoder's own trim information, which offload
            // skips. Requiring it here would disable offload on exactly the
            // formats worth offloading.
            .setIsGaplessSupportRequired(false)
            .build()
    }

// Media3 reads every manifest and segment through this, which is the only place
// an interceptor can sit. The stock data source has nowhere to put one, so
// without this seam the codec repair could not exist at all.
//
// OkHttp is the upstream, not the whole of it. An OkHttpDataSource can only
// fetch http and https. Handing Media3 one on its
// own took fifteen device tests down at once — every gate that plays a local
// file, because there is no such thing as an OkHttp request for
// /data/user/0/.../ladder.mp4. In a shipped build that is downloaded media and
// anything a viewer opened from their own storage, which is precisely the case
// least likely to be noticed by a developer testing against a server.
//
// DefaultDataSource handles file, asset, content and raw resources itself and
// only delegates to the upstream for network schemes, so the interceptor sits
// where it is needed and nothing else changes.
//
// The extractors are stock, and deliberately have no ASS subtitle parser wired
// into them. Both routes into one funnel a whole .ass body through
// ResponseBody.bytes(), so a karaoke track of ninety megabytes becomes a
// transient heap peak near a hundred and eighty — which OOMs a television with
// a 256MB heap and is not comfortable on a phone either. Sidecar ASS is drawn
// by the subtitle overlay, which streams it, and NoMercy serves HLS rather than
// MKV-embedded ASS, so nothing is lost by leaving the extractor path alone.
private fun dataSourceFactory(context: Context, auth: AuthHeaders): DataSource.Factory {
    val client = OkHttpClient.Builder()
        // Auth first, so the repair below sees the response that the
        // authenticated request actually returned rather than a 401 body.
        .addInterceptor(auth.asInterceptor())
        .addInterceptor(CodecFixInterceptor())
        .build()

    return DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
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
