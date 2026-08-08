// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import platform.AVFoundation.AVURLAsset
import platform.Foundation.NSURL

/**
 * What AVPlayer will open, asked of AVFoundation rather than listed by hand.
 *
 * Apple has no MediaCodecList to enumerate, but it will answer whether it
 * plays a given codec if it is asked in the form of a MIME type — which is what
 * `AVURLAsset.isPlayableExtendedMIMEType` is for. Every chip generation answers
 * differently and a hand-written list would be a list about whichever Mac
 * happened to be on the desk.
 *
 * There is no software fallback here. AVPlayer is the engine on Apple; libmpv
 * has no payload for these targets, so what the hardware declines does not
 * play, and saying otherwise would have the server send a stream nothing here
 * can open.
 */
public actual fun platformPlaybackCapabilities(): PlaybackCapabilities {
    fun plays(mimeType: String): Boolean = AVURLAsset.isPlayableExtendedMIMEType(mimeType)

    return PlaybackCapabilities(
        videoCodecs = VIDEO_PROBES.filter { (probe, _) -> plays(probe) }.map { (_, codec) -> codec },
        audioCodecs = AUDIO_PROBES.filter { (probe, _) -> plays(probe) }.map { (_, codec) -> codec },
        // Both the display's business rather than the decoder's on these
        // devices, and both change with the route — an Apple TV's answer
        // depends on the television it is plugged into today.
        maxVideoHeight = null,
        maxAudioChannels = null,
        hdrSupport = plays("""video/mp4; codecs="hvc1.2.4.L120.90""""),
        dolbyVision = if (plays(DOLBY_VISION_PROBE)) DolbyVisionProfile.PROFILE_5 else DolbyVisionProfile.NONE,
        notes = "AVPlayer only, no software decoder; 10-bit AVC " +
            (if (plays(HIGH_10_PROBE)) "decodes" else "does NOT decode"),
    )
}

// The MIME type each wire codec name is probed with, as a table rather than a
// run of ifs — the run was one line repeated with two words changed, which is
// where a copied line keeps the previous line's codec.
private const val DOLBY_VISION_PROBE: String = """video/mp4; codecs="dvh1.05.06""""

private const val HIGH_10_PROBE: String = """video/mp4; codecs="avc1.6E0028""""

private val VIDEO_PROBES: List<Pair<String, String>> = listOf(
    """video/mp4; codecs="avc1.640028"""" to PlaybackCodec.H264,
    """video/mp4; codecs="hvc1.1.6.L120.90"""" to PlaybackCodec.HEVC,
    """video/mp4; codecs="av01.0.08M.08"""" to PlaybackCodec.AV1,
    """video/mp4; codecs="vp09.00.10.08"""" to PlaybackCodec.VP9,
)

private val AUDIO_PROBES: List<Pair<String, String>> = listOf(
    """audio/mp4; codecs="mp4a.40.2"""" to PlaybackCodec.AAC,
    """audio/mp4; codecs="ac-3"""" to PlaybackCodec.AC3,
    """audio/mp4; codecs="ec-3"""" to PlaybackCodec.EAC3,
    """audio/mp4; codecs="fLaC"""" to PlaybackCodec.FLAC,
    """audio/mp4; codecs="Opus"""" to PlaybackCodec.OPUS,
)
