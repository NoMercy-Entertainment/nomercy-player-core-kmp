// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import platform.AVFoundation.AVURLAsset

/**
 * What AVPlayer will open, asked of AVFoundation rather than listed by hand.
 *
 * There is no software fallback here: AVPlayer is the engine on Apple, libmpv
 * has no payload for these targets, and what the hardware declines does not
 * play. Saying otherwise would have the server send a stream nothing here can
 * open — the same mistake as declaring 10-bit on an Android phone.
 */
public actual fun platformDecodeProfile(): DeviceDecodeProfile {
    fun plays(mimeType: String): Boolean = AVURLAsset.isPlayableExtendedMIMEType(mimeType)

    val hdr: List<String> = HDR_PROBES.filter { (probe, _) -> plays(probe) }.map { (_, format) -> format }.distinct()

    val video: List<VideoCodecCapability> = VIDEO_CODEC_PROBES.mapNotNull { entry ->
        val codec = entry.codec
        if (!entry.profiles.any { (probe, _) -> plays(probe) }) return@mapNotNull null

        val profiles: List<String> = entry.profiles.filter { (probe, _) -> plays(probe) }.map { (_, name) -> name }
        val maxBitDepth: Int = if (entry.tenBitProbes.any(::plays)) 10 else 8

        VideoCodecCapability(
            codec = codec,
            profiles = profiles,
            maxBitDepth = maxBitDepth,
            // Left at the platform ceiling rather than guessed. On an Apple TV
            // the panel is whatever is plugged in today, and AVFoundation has
            // no API answering the attached display's actual resolution.
            maxWidth = DecodeResolution.UHD,
            maxHeight = DecodeResolution.UHD,
            maxFramerate = MAX_FRAMERATE,
            hdrFormats = if (codec == DecodeCodec.H265 || codec == DecodeCodec.AV1) hdr else emptyList(),
            maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
        )
    }

    val audio: List<AudioCodecCapability> = AUDIO_PROBES.mapNotNull { (probe, codec) ->
        if (!plays(probe)) return@mapNotNull null
        AudioCodecCapability(
            codec = codec,
            maxChannels = if (codec == DecodeCodec.AAC) DeviceDecodeProfile.STEREO else MAX_SURROUND_CHANNELS,
            // AVPlayer decodes what it plays; passthrough is a receiver-attached
            // question tvOS answers through AVAudioSession, not through this
            // MIME probe, so this actual claims decode only.
            passthrough = false,
            decode = true,
        )
    }

    return DeviceDecodeProfile(
        video = video,
        audio = audio,
        // HLS is the one Apple guarantees; the rest go through the same player.
        containers = listOf(DecodeContainer.HLS, DecodeContainer.MP4),
        supportsHdr = hdr.isNotEmpty(),
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

private const val MAX_FRAMERATE: Int = 60
private const val MAX_SURROUND_CHANNELS: Int = 6

// The probe string each HDR format is asked with, so a chip that plays HLG
// but not Dolby Vision names only HLG rather than losing that to a single
// flag.
private val HDR_PROBES: List<Pair<String, String>> = listOf(
    """video/mp4; codecs="hvc1.2.4.L120.B0"""" to HdrFormat.HDR10,
    """video/mp4; codecs="dvh1.05.06"""" to HdrFormat.DOLBY_VISION,
    """video/mp4; codecs="hvc1.2.20.L120.B0"""" to HdrFormat.HLG,
)

private class CodecProbe(
    val codec: String,
    val profiles: List<Pair<String, String>>,
    val tenBitProbes: List<String>,
)

// The probe string each wire name/profile is asked with, as a table rather
// than a run of ifs — the run was one line repeated with two words changed,
// which is where a copied line keeps the previous line's codec.
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
            """video/mp4; codecs="hvc1.1.6.L150.B0"""" to "main",
            """video/mp4; codecs="hvc1.2.4.L120.B0"""" to "main10",
            """video/mp4; codecs="hev1.2.4.L120.B0"""" to "main10",
        ),
        tenBitProbes = listOf(
            """video/mp4; codecs="hvc1.2.4.L120.B0"""",
            """video/mp4; codecs="hev1.2.4.L120.B0"""",
        ),
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
    """audio/mp4; codecs="fLaC"""" to DecodeCodec.FLAC,
    """audio/mp4; codecs="Opus"""" to DecodeCodec.OPUS,
    """audio/mpeg""" to DecodeCodec.MP3,
)
