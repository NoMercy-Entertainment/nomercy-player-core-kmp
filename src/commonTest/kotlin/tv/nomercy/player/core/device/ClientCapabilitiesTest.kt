// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The canonical Samsung-phone example from the device-capability-contract
// spec: HEVC Main10 decodes, AVC High10 does not, AAC decodes only, AC3 is
// passthrough-only, DTS is absent entirely, containers are mp4/mkv/ts.
//
// Per-codec is the whole point of this shape: a flat `videoCodecs` list plus
// one `supports10Bit` boolean has no way to say HEVC opens 10-bit while AVC
// does not, which is exactly what this device does.
class ClientCapabilitiesTest {

    private val samsungPhone = DeviceDecodeProfile(
        video = listOf(
            VideoCodecCapability(
                codec = DecodeCodec.H264,
                profiles = listOf("high", "main"),
                maxBitDepth = 8,
                maxWidth = DecodeResolution.UHD,
                maxHeight = DecodeResolution.UHD,
                maxFramerate = 60,
                hdrFormats = emptyList(),
            ),
            VideoCodecCapability(
                codec = DecodeCodec.H265,
                profiles = listOf("main", "main10"),
                maxBitDepth = 10,
                maxWidth = DecodeResolution.UHD,
                maxHeight = DecodeResolution.UHD,
                maxFramerate = 60,
                hdrFormats = listOf(HdrFormat.HDR10, HdrFormat.HLG),
            ),
        ),
        audio = listOf(
            AudioCodecCapability(codec = DecodeCodec.AAC, maxChannels = 2, passthrough = false, decode = true),
            AudioCodecCapability(codec = DecodeCodec.AC3, maxChannels = 6, passthrough = true, decode = false),
        ),
        containers = listOf(DecodeContainer.MP4, DecodeContainer.MKV, DecodeContainer.TS),
        supportsHdr = true,
    )

    @Test
    fun aCodecThatDecodes10BitNamesTheProfileTheOtherCodecCannotClaim() {
        val h265 = samsungPhone.video.first { it.codec == DecodeCodec.H265 }
        val h264 = samsungPhone.video.first { it.codec == DecodeCodec.H264 }

        assertTrue("main10" in h265.profiles, "HEVC must claim main10: ${h265.profiles}")
        assertEquals(10, h265.maxBitDepth)

        assertFalse("high10" in h264.profiles, "AVC must not claim high10: ${h264.profiles}")
        assertEquals(8, h264.maxBitDepth)
    }

    @Test
    fun dtsAbsentFromTheListMeansUnsupportedNotJustUnlisted() {
        assertFalse(samsungPhone.audio.any { it.codec == DecodeCodec.DTS }, "DTS must be absent, not merely unclaimed")
        assertFalse(samsungPhone.audio.any { it.codec == DecodeCodec.TRUEHD }, "TrueHD must be absent, not merely unclaimed")
    }

    @Test
    fun ac3IsPassthroughOnlyWhileAacIsDecodeOnly() {
        val ac3 = samsungPhone.audio.first { it.codec == DecodeCodec.AC3 }
        val aac = samsungPhone.audio.first { it.codec == DecodeCodec.AAC }

        assertTrue(ac3.passthrough, "AC3 must be passthrough")
        assertFalse(ac3.decode, "AC3 must not claim decode on this device")

        assertTrue(aac.decode, "AAC must decode")
        assertFalse(aac.passthrough, "AAC must not claim passthrough")
    }

    @Test
    fun containersNameMp4MkvAndTs() {
        assertEquals(
            setOf(DecodeContainer.MP4, DecodeContainer.MKV, DecodeContainer.TS),
            samsungPhone.containers.toSet(),
        )
    }

    @Test
    fun theInterimTenBitBooleanIsDerivableFromMaxBitDepthAlone() {
        // The migration note: `video.all { it.maxBitDepth >= 10 }` reproduces
        // the old collapsed `supports10Bit` flag for any consumer still
        // reading it during the platform-actual rollout window.
        assertFalse(
            samsungPhone.video.all { it.maxBitDepth >= 10 },
            "the derived legacy flag must be false: AVC on this device is 8-bit-only",
        )
    }

    @Test
    fun noCapIsTheDefaultBitrateCeiling() {
        assertEquals(DeviceDecodeProfile.NO_CAP, DeviceDecodeProfile().maxBitrateKbps)
        assertEquals(0, DeviceDecodeProfile.NO_CAP)
    }
}
