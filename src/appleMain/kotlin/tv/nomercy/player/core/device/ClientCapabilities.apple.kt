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

    return DeviceDecodeProfile(
        videoCodecs = VIDEO_PROBES.filter { (probe, _) -> plays(probe) }.map { (_, codec) -> codec },
        audioCodecs = AUDIO_PROBES.filter { (probe, _) -> plays(probe) }.map { (_, codec) -> codec },
        // HLS is the one Apple guarantees; the rest go through the same player.
        containers = listOf(DecodeContainer.HLS, DecodeContainer.MP4),
        // Left unset rather than guessed. On an Apple TV the answer is the
        // television's, and it changes with whatever it is plugged into today.
        maxWidth = null,
        maxHeight = null,
        supportsHdr = plays(HDR_PROBE),
        // A decoder trait, probed as one: Apple silicon opens Main 10 and High
        // 10 where older chips do not, and HDR only implies it.
        supports10Bit = TEN_BIT_PROBES.any(::plays),
        maxAudioChannels = DeviceDecodeProfile.STEREO,
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

// The probe string each wire name is asked with, as a table rather than a run
// of ifs — the run was one line repeated with two words changed, which is where
// a copied line keeps the previous line's codec.
private const val HDR_PROBE: String = """video/mp4; codecs="hvc1.2.4.L120.B0""""

private val TEN_BIT_PROBES: List<String> = listOf(
    """video/mp4; codecs="hvc1.2.4.L120.B0"""",
    """video/mp4; codecs="hev1.2.4.L120.B0"""",
    """video/mp4; codecs="avc1.6E0033"""",
    """video/mp4; codecs="av01.0.08M.10"""",
)

private val VIDEO_PROBES: List<Pair<String, String>> = listOf(
    """video/mp4; codecs="avc1.42E01E"""" to DecodeCodec.H264,
    """video/mp4; codecs="hvc1.1.6.L150.B0"""" to DecodeCodec.H265,
    """video/mp4; codecs="av01.0.05M.08"""" to DecodeCodec.AV1,
)

private val AUDIO_PROBES: List<Pair<String, String>> = listOf(
    """audio/mp4; codecs="mp4a.40.2"""" to DecodeCodec.AAC,
    """audio/mp4; codecs="ec-3"""" to DecodeCodec.EAC3,
    """audio/mp4; codecs="ac-3"""" to DecodeCodec.AC3,
    """audio/mp4; codecs="fLaC"""" to DecodeCodec.FLAC,
    """audio/mp4; codecs="Opus"""" to DecodeCodec.OPUS,
    """audio/mpeg""" to DecodeCodec.MP3,
)
