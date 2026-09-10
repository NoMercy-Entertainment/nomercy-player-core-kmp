// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider
import java.awt.GraphicsEnvironment

/**
 * The desktop decodes whatever ffmpeg inside libmpv was built with, which
 * covers everything in this list and a great deal beyond it.
 *
 * Conditional on the payload having installed, not on the library having been
 * compiled: a machine with no payload has no engine at all, and an empty list
 * makes the server transcode rather than sending a stream nothing can open.
 */
public actual fun platformDecodeProfile(): DeviceDecodeProfile {
    if (!MpvVideoEngineProvider.isAvailable()) return DeviceDecodeProfile()

    val screen: DecodeBounds = screenBounds()

    return DeviceDecodeProfile(
        // H264 first, as web does it, because the server transcodes to the
        // first listed codec and that is the one every downstream device and
        // cast target also opens.
        video = listOf(
            video(DecodeCodec.H264, H264_PROFILES, screen),
            video(DecodeCodec.H265, listOf(VideoProfileName.MAIN, VideoProfileName.MAIN10), screen),
            video(DecodeCodec.AV1, listOf(VideoProfileName.MAIN, VideoProfileName.MAIN10), screen),
            video(DecodeCodec.VP9, listOf(VP9_PROFILE0, VP9_PROFILE2), screen),
        ),
        audio = listOf(
            audio(DecodeCodec.AAC),
            audio(DecodeCodec.EAC3),
            audio(DecodeCodec.AC3),
            audio(DecodeCodec.DTS),
            audio(DecodeCodec.TRUEHD),
            audio(DecodeCodec.FLAC),
            audio(DecodeCodec.OPUS),
            audio(DecodeCodec.MP3),
        ),
        containers = listOf(
            DecodeContainer.HLS,
            DecodeContainer.MP4,
            DecodeContainer.WEBM,
            DecodeContainer.DASH,
            DecodeContainer.MKV,
            DecodeContainer.TS,
        ),
        // Not probed. A desktop's HDR output depends on the panel, the cable
        // and the compositor, and none of the three answers through a JVM API —
        // so this says no rather than guessing, and an SDR picture one rung
        // lower is the cost of being wrong that way. Per-codec [hdrFormats]
        // above stays a decode ceiling regardless: libmpv tone-maps HDR to
        // whatever the display actually is.
        supportsHdr = false,
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

// What the panel reports, clamped to a rung, or the ceiling when the JVM
// cannot see a display at all.
private class DecodeBounds(val maxWidth: Int, val maxHeight: Int)

private fun screenBounds(): DecodeBounds {
    val mode = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
    }.getOrNull() ?: return DecodeBounds(DecodeResolution.UHD, DecodeResolution.UHD)

    return DecodeBounds(
        maxWidth = DecodeResolution.clamp(maxOf(mode.width, mode.height)),
        maxHeight = DecodeResolution.clamp(minOf(mode.width, mode.height)),
    )
}

private fun video(codec: String, profiles: List<String>, screen: DecodeBounds): VideoCodecCapability =
    VideoCodecCapability(
        codec = codec,
        profiles = profiles,
        maxBitDepth = MAX_BIT_DEPTH,
        maxWidth = screen.maxWidth,
        maxHeight = screen.maxHeight,
        maxFramerate = MAX_FRAMERATE,
        hdrFormats = ALL_HDR_FORMATS,
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )

private fun audio(codec: String): AudioCodecCapability = AudioCodecCapability(
    codec = codec,
    maxChannels = MAX_SURROUND_CHANNELS,
    passthrough = false,
    decode = true,
)

// libmpv tone-maps whatever it opens rather than gating on it, so every format
// and profile named here is a ceiling ffmpeg clears, not a hardware answer the
// way the Android/Apple actuals give one.
private val ALL_HDR_FORMATS: List<String> = listOf(
    HdrFormat.HDR10,
    HdrFormat.HDR10_PLUS,
    HdrFormat.DOLBY_VISION,
    HdrFormat.HLG,
)

private val H264_PROFILES: List<String> = listOf(
    VideoProfileName.BASELINE,
    VideoProfileName.MAIN,
    VideoProfileName.HIGH,
    VideoProfileName.HIGH10,
)

// VP9 names its profiles by number rather than by the words the other codecs
// share, so these are not VideoProfileName entries.
private const val VP9_PROFILE0: String = "profile0"
private const val VP9_PROFILE2: String = "profile2"

private const val MAX_BIT_DEPTH: Int = 12
private const val MAX_FRAMERATE: Int = 120
private const val MAX_SURROUND_CHANNELS: Int = 8
