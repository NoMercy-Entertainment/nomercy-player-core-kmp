// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.device

// Probed rather than declared, same as every other actual of this port:
// MediaSource.isTypeSupported is the browser's own real answer for what its
// MSE pipeline can demux+decode, not a guess about "what browsers usually
// support". H264 is checked first — the server transcodes to the first
// listed codec, and it is the one every Cast/STB target reliably opens.
@JsFun(
    """(mime) => {
        try {
            return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime);
        } catch (e) {
            return false;
        }
    }""",
)
private external fun jsIsTypeSupported(mime: JsString): Boolean

@JsFun("() => window.screen.width * (window.devicePixelRatio || 1)")
private external fun jsScreenWidth(): Double

@JsFun("() => window.screen.height * (window.devicePixelRatio || 1)")
private external fun jsScreenHeight(): Double

private fun supported(mime: String): Boolean = jsIsTypeSupported(mime.toJsString())

public actual fun platformDecodeProfile(): DeviceDecodeProfile {
    val maxWidth: Int = runCatching { DecodeResolution.clamp(jsScreenWidth().toInt()) }.getOrDefault(DecodeResolution.HD)
    val maxHeight: Int = runCatching { DecodeResolution.clamp(jsScreenHeight().toInt()) }.getOrDefault(DecodeResolution.HD)

    val video: List<VideoCodecCapability> = VIDEO_CODEC_PROBES.mapNotNull { entry ->
        val profiles: List<String> = entry.profiles.filter { (probe, _) -> supported(probe) }.map { (_, name) -> name }
        if (profiles.isEmpty()) return@mapNotNull null

        val maxBitDepth: Int = if (entry.tenBitProbes.any(::supported)) 10 else 8
        val hdr: List<String> = HDR_PROBES.filter { (probe, _) -> supported(probe) }.map { (_, format) -> format }.distinct()

        VideoCodecCapability(
            codec = entry.codec,
            profiles = profiles,
            maxBitDepth = maxBitDepth,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxFramerate = MAX_FRAMERATE,
            // Codec/HDR interplay MSE cannot answer directly: a browser that
            // reports the profile string at all is asked to decode it, so the
            // codecs known to carry HDR metadata get the display-level probe,
            // the way the Android/Apple actuals restrict HDR to HEVC/AV1/VP9.
            hdrFormats = if (entry.codec == DecodeCodec.H265 || entry.codec == DecodeCodec.AV1) hdr else emptyList(),
            maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
        )
    }

    val audio: List<AudioCodecCapability> = AUDIO_PROBES.mapNotNull { (probe, codec) ->
        if (!supported(probe)) return@mapNotNull null
        AudioCodecCapability(
            codec = codec,
            maxChannels = if (codec == DecodeCodec.AAC) DeviceDecodeProfile.STEREO else MAX_SURROUND_CHANNELS,
            // No browser API answers whether the attached receiver accepts a
            // bitstream unmodified — MSE demuxes into a decoder, it never
            // passes an encoded frame through untouched.
            passthrough = false,
            decode = true,
        )
    }

    // No MediaSource probe for these: HLS/DASH are manifest formats MSE
    // consumes segment-by-segment (there is no "video/vnd.apple.mpegurl" MIME
    // MSE itself accepts), so declaring them tracks what nomercy-video-player's
    // existing JS library already ships against, the same browser engine.
    val containers = listOf(DecodeContainer.HLS, DecodeContainer.MP4, DecodeContainer.DASH)

    return DeviceDecodeProfile(
        video = video,
        audio = audio,
        containers = containers,
        // Not probed. No browser API answers whether the attached TV panel
        // itself does HDR — same "say no rather than guess" call every other
        // actual of this port makes for the same reason.
        supportsHdr = false,
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

private const val MAX_FRAMERATE: Int = 60
private const val MAX_SURROUND_CHANNELS: Int = 6

// The MIME string each HDR format is asked with, so a browser that reports
// HLG but not Dolby Vision names only HLG rather than losing that to a single
// flag.
private val HDR_PROBES: List<Pair<String, String>> = listOf(
    """video/mp4; codecs="hvc1.2.4.L93.B0"""" to HdrFormat.HDR10,
    """video/mp4; codecs="dvh1.05.03"""" to HdrFormat.DOLBY_VISION,
    """video/mp4; codecs="hvc1.2.20.L93.B0"""" to HdrFormat.HLG,
)

private class CodecProbe(
    val codec: String,
    val profiles: List<Pair<String, String>>,
    val tenBitProbes: List<String>,
)

private val VIDEO_CODEC_PROBES: List<CodecProbe> = listOf(
    CodecProbe(
        codec = DecodeCodec.H264,
        profiles = listOf(
            """video/mp4; codecs="avc1.42E01E"""" to "baseline",
            """video/mp4; codecs="avc1.4D401E"""" to "main",
            """video/mp4; codecs="avc1.640028"""" to "high",
            """video/mp4; codecs="avc1.6E0033"""" to "high10",
        ),
        tenBitProbes = listOf("""video/mp4; codecs="avc1.6E0033""""),
    ),
    CodecProbe(
        codec = DecodeCodec.H265,
        profiles = listOf(
            """video/mp4; codecs="hvc1.1.6.L93.B0"""" to "main",
            """video/mp4; codecs="hvc1.2.4.L93.B0"""" to "main10",
        ),
        tenBitProbes = listOf("""video/mp4; codecs="hvc1.2.4.L93.B0""""),
    ),
    CodecProbe(
        codec = DecodeCodec.AV1,
        profiles = listOf(
            """video/mp4; codecs="av01.0.05M.08"""" to "main",
            """video/mp4; codecs="av01.0.08M.10"""" to "main10",
        ),
        tenBitProbes = listOf("""video/mp4; codecs="av01.0.08M.10""""),
    ),
)

private val AUDIO_PROBES: List<Pair<String, String>> = listOf(
    """audio/mp4; codecs="mp4a.40.2"""" to DecodeCodec.AAC,
    """audio/mp4; codecs="ec-3"""" to DecodeCodec.EAC3,
    """audio/mp4; codecs="ac-3"""" to DecodeCodec.AC3,
    """audio/mp4; codecs="flac"""" to DecodeCodec.FLAC,
    """audio/webm; codecs="opus"""" to DecodeCodec.OPUS,
    """audio/mpeg""" to DecodeCodec.MP3,
)
