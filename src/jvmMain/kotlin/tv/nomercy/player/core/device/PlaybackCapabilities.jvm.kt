// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * The desktop decodes whatever ffmpeg inside libmpv was built with, which is
 * everything in this list and a great deal more.
 *
 * Conditional on the payload having installed, not on the library having been
 * compiled. A machine with no payload has no engine at all, and a device that
 * asks for a stream it cannot then play is worse off than one that never asked.
 */
public actual fun platformPlaybackCapabilities(): PlaybackCapabilities =
    if (MpvVideoEngineProvider.isAvailable()) {
        PlaybackCapabilities(
            videoCodecs = listOf(
                PlaybackCodec.H264,
                PlaybackCodec.HEVC,
                PlaybackCodec.AV1,
                PlaybackCodec.VP9,
            ),
            audioCodecs = listOf(
                PlaybackCodec.AAC,
                PlaybackCodec.AC3,
                PlaybackCodec.EAC3,
                PlaybackCodec.FLAC,
                PlaybackCodec.OPUS,
                PlaybackCodec.TRUEHD,
                PlaybackCodec.DTS,
            ),
            // Uncapped. The pane's own size caps the ladder, measured every
            // layout pass, and a number written down here would be a second
            // ceiling disagreeing with the real one.
            maxVideoHeight = null,
            maxAudioChannels = null,
            hdrSupport = false,
            notes = "libmpv on the desktop; 10-bit AVC decodes; ffmpeg decodes in " +
                "software, so this list is not the limit",
        )
    } else {
        PlaybackCapabilities(
            videoCodecs = emptyList(),
            audioCodecs = emptyList(),
            maxVideoHeight = null,
            maxAudioChannels = null,
            notes = "no video engine is available on this host: ${MpvVideoEngineProvider.whyUnavailable()}",
        )
    }
