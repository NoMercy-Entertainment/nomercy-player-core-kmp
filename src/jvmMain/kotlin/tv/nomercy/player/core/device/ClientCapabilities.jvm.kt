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

    val bounds = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
    }.getOrNull()

    return DeviceDecodeProfile(
        // H264 first, as web does it, because the server transcodes to the
        // first listed codec and that is the one every downstream device and
        // cast target also opens.
        videoCodecs = listOf(DecodeCodec.H264, DecodeCodec.H265, DecodeCodec.AV1),
        audioCodecs = listOf(
            DecodeCodec.AAC,
            DecodeCodec.EAC3,
            DecodeCodec.AC3,
            DecodeCodec.FLAC,
            DecodeCodec.OPUS,
            DecodeCodec.MP3,
        ),
        containers = listOf(
            DecodeContainer.HLS,
            DecodeContainer.MP4,
            DecodeContainer.WEBM,
            DecodeContainer.DASH,
        ),
        maxWidth = bounds?.let { mode -> DecodeResolution.clamp(maxOf(mode.width, mode.height)) },
        maxHeight = bounds?.let { mode -> DecodeResolution.clamp(minOf(mode.width, mode.height)) },
        // Not probed. A desktop's HDR output depends on the panel, the cable
        // and the compositor, and none of the three answers through a JVM API —
        // so this says no rather than guessing, and an SDR picture one rung
        // lower is the cost of being wrong that way.
        supportsHdr = false,
        // ffmpeg decodes 10-bit everything, which is the whole reason the
        // desktop plays the files a phone cannot.
        supports10Bit = true,
        maxAudioChannels = DeviceDecodeProfile.STEREO,
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}
