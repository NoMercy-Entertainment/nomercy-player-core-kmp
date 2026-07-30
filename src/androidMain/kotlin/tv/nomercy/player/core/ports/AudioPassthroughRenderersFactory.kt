// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

// Renderers wired so an AVR receives the bitstream a disc would have carried.
//
// A television is usually the middle of a chain rather than the end of it. If
// the player decodes Atmos to PCM and sends that on, the receiver has nothing
// left to decode and the room falls back to whatever the TV can do by itself —
// which is the difference between a soundtrack and two speakers.
//
// Float output is off, always, and that is the load-bearing part. Float PCM is
// higher quality on a path that decodes, and on a path that does not it is
// fatal: the framework up-converts before the frames reach AudioTrack, so what
// arrives at the HDMI HAL is no longer the compressed stream it was supposed to
// forward.
//
// Gated on form factor rather than on what the sink advertises, because phones
// are surrounded by Bluetooth and USB-C dongles that claim surround support they
// do not have. A television is plugged into something by wire or it is not.
@OptIn(UnstableApi::class)
// Open, with a constructor a subclass can reach, because one subclass exists in
// this module: the variant that splices the equaliser into the sink. Media3
// builds its audio sink inside the renderers factory, so overriding the method
// that makes it is the only way in.
public open class AudioPassthroughRenderersFactory protected constructor(
    context: Context,
    enablePassthrough: Boolean,
) : DefaultRenderersFactory(context) {

    private val capabilities: AudioCapabilities = AudioCapabilities.getCapabilities(context)

    private val codecAdapters: ToneMappingCodecAdapterFactory = ToneMappingCodecAdapterFactory(context)

    // Whether the video decoder is being asked to hand back SDR frames.
    //
    // Lives on the renderers factory because that is the only object still
    // reachable once the engine is built: Media3 verifies the calling thread on
    // every player setter, and the codec adapter factory is consulted per codec
    // configuration rather than once, so flipping this is what makes the decision
    // apply to the next item without rebuilding the engine.
    public var requestSdrToneMap: Boolean
        get() = codecAdapters.toneMapToSdr
        set(value) {
            codecAdapters.toneMapToSdr = value
        }

    // The seam the tone-map request rides in on. DefaultRenderersFactory hands
    // whatever this returns to MediaCodecVideoRenderer.Builder.
    protected override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = codecAdapters

    // Whether to ask the track selector for offload as well. Passthrough is two
    // halves — the sink must not up-convert, and the selector must request that
    // compressed frames go straight to the HAL — and both are needed. This is
    // how the other half learns whether to bother.
    public val deviceSupportsSurround: Boolean = enablePassthrough && hasSurroundCapability(capabilities)

    // The encodings this sink actually advertises, for a host that wants to say
    // so in a settings screen. Empty on a phone by construction, not by
    // accident: the passthrough gate is applied before the question is asked.
    public val advertisedEncodings: List<String>
        get() = if (!deviceSupportsSurround) {
            emptyList()
        } else {
            SURROUND_ENCODINGS.filter { capabilities.supportsEncoding(it.first) }.map { it.second }
        }

    init {
        // A decoder that refuses a stream should hand it to the next one rather
        // than end playback. On television hardware the first choice is often a
        // vendor decoder with narrower support than the software fallback.
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        // Passthrough audio renderers initialise slower than PCM ones, and the
        // default joining window is short enough that the video starts without
        // them — which is a silent first second on exactly the setup this class
        // exists for.
        setAllowedVideoJoiningTimeMs(AUDIO_JOINING_WINDOW_MS)
    }

    // One sink, not two. The app this came from branched on
    // deviceSupportsSurround and built the identical sink in both arms — same
    // float setting, same playback params — with only a log line between them.
    // Keeping the branch would have suggested the two paths differ here, and
    // the next person would look for the difference. They differ on the
    // selector, not on the sink.
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()

    public companion object {

        // In preference order, best first. All of them are evaluated rather
        // than short-circuited so a host can report everything the sink claims.
        public val SURROUND_ENCODINGS: List<Pair<Int, String>> = listOf(
            C.ENCODING_DOLBY_TRUEHD to "TrueHD/Atmos",
            C.ENCODING_E_AC3_JOC to "EAC3-JOC (Atmos)",
            C.ENCODING_E_AC3 to "EAC3 (DD+)",
            C.ENCODING_AC3 to "AC3 (DD)",
            C.ENCODING_DTS_HD to "DTS-HD MA",
            C.ENCODING_DTS to "DTS",
        )

        public fun hasSurroundCapability(capabilities: AudioCapabilities): Boolean =
            SURROUND_ENCODINGS.any { capabilities.supportsEncoding(it.first) }

        public fun create(context: Context, isTvForm: Boolean): AudioPassthroughRenderersFactory =
            AudioPassthroughRenderersFactory(context = context, enablePassthrough = isTvForm)
    }
}

private const val AUDIO_JOINING_WINDOW_MS = 5_000L
