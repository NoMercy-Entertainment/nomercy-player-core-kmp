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

    val video: List<VideoCodecCapability> = VIDEO_CODEC_PROBES
        .filter { entry -> entry.profiles.any { (probe, _) -> plays(probe) } }
        .map { entry ->
            val codec = entry.codec
            val profiles: List<String> = entry.profiles.filter { (probe, _) -> plays(probe) }.map { (_, name) -> name }
            val maxBitDepth: Int = if (entry.tenBitProbes.any(::plays)) TEN_BIT_DEPTH else EIGHT_BIT_DEPTH

            VideoCodecCapability(
                codec = codec,
                profiles = profiles,
                maxBitDepth = maxBitDepth,
                // Left at the platform ceiling rather than guessed. On an Apple
                // TV the panel is whatever is plugged in today, and AVFoundation
                // has no API answering the attached display's actual resolution.
                maxWidth = DecodeResolution.UHD,
                maxHeight = DecodeResolution.UHD,
                maxFramerate = MAX_FRAMERATE,
                hdrFormats = if (codec == DecodeCodec.H265 || codec == DecodeCodec.AV1) hdr else emptyList(),
                maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
            )
        }

    val audio: List<AudioCodecCapability> = AUDIO_PROBES
        .filter { (probe, _) -> plays(probe) }
        .map { (_, codec) ->
            AudioCodecCapability(
                codec = codec,
                maxChannels = if (codec == DecodeCodec.AAC) DeviceDecodeProfile.STEREO else MAX_SURROUND_CHANNELS,
                // AVPlayer decodes what it plays; passthrough is a
                // receiver-attached question tvOS answers through
                // AVAudioSession, not through this MIME probe, so this actual
                // claims decode only.
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
private const val TEN_BIT_DEPTH: Int = 10
private const val EIGHT_BIT_DEPTH: Int = 8

// The probe string each HDR format is asked with, so a chip that plays HLG
// but not Dolby Vision names only HLG rather than losing that to a single
// flag.
private val HDR_PROBES: List<Pair<String, String>> = listOf(
    mp4Codec("hvc1.2.4.L120.B0") to HdrFormat.HDR10,
    mp4Codec("dvh1.05.06") to HdrFormat.DOLBY_VISION,
    mp4Codec("hvc1.2.20.L120.B0") to HdrFormat.HLG,
)

// The container/codecs MIME shape AVFoundation is probed with, written once.
private fun mp4Codec(spec: String): String = """video/mp4; codecs="$spec""""

private fun mp4Audio(spec: String): String = """audio/mp4; codecs="$spec""""

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
            mp4Codec("avc1.42E01E") to VideoProfileName.BASELINE,
            mp4Codec("avc1.4D401E") to VideoProfileName.MAIN,
            mp4Codec("avc1.640028") to VideoProfileName.HIGH,
            mp4Codec("avc1.6E0033") to VideoProfileName.HIGH10,
        ),
        tenBitProbes = listOf(mp4Codec("avc1.6E0033")),
    ),
    CodecProbe(
        codec = DecodeCodec.H265,
        profiles = listOf(
            mp4Codec("hvc1.1.6.L150.B0") to VideoProfileName.MAIN,
            mp4Codec("hvc1.2.4.L120.B0") to VideoProfileName.MAIN10,
            mp4Codec("hev1.2.4.L120.B0") to VideoProfileName.MAIN10,
        ),
        tenBitProbes = listOf(
            mp4Codec("hvc1.2.4.L120.B0"),
            mp4Codec("hev1.2.4.L120.B0"),
        ),
    ),
    CodecProbe(
        codec = DecodeCodec.AV1,
        profiles = listOf(
            mp4Codec("av01.0.05M.08") to VideoProfileName.MAIN,
            mp4Codec("av01.0.08M.10") to VideoProfileName.MAIN10,
        ),
        tenBitProbes = listOf(mp4Codec("av01.0.08M.10")),
    ),
)

private val AUDIO_PROBES: List<Pair<String, String>> = listOf(
    mp4Audio("mp4a.40.2") to DecodeCodec.AAC,
    mp4Audio("ec-3") to DecodeCodec.EAC3,
    mp4Audio("ac-3") to DecodeCodec.AC3,
    mp4Audio("fLaC") to DecodeCodec.FLAC,
    mp4Audio("Opus") to DecodeCodec.OPUS,
    """audio/mpeg""" to DecodeCodec.MP3,
)
