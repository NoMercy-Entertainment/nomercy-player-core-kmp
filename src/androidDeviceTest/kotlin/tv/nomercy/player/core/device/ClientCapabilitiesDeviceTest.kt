// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import androidx.test.platform.app.InstrumentationRegistry
import tv.nomercy.player.core.ports.PlatformContext
import tv.nomercy.player.core.ports.PlatformEnvironment
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probe against a real device's real decoder list.
 *
 * A JVM test of this proves nothing: MediaCodecList does not exist off a
 * device, and the whole question is what THIS device answers. The failure this
 * guards is a client declaring a decode capability it does not have, which the
 * server believes, and which reaches the viewer as a black picture with no
 * error anywhere in the chain.
 */
class ClientCapabilitiesDeviceTest {

    private fun capabilities(): DeviceDecodeProfile {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PlatformEnvironment.install(PlatformContext(context.applicationContext))
        return platformDecodeProfile().also { probed ->
            // Logged, because a passing assertion says the relationship holds and
            // says nothing about what this device actually answered — and what it
            // answered is the thing anybody debugging a transcode decision needs.
            android.util.Log.i("NoMercyCaps", probed.toString())
        }
    }

    // Every device decodes AVC. A client that cannot say so would have the
    // server transcode every file in the library.
    @Test
    fun theDeviceDeclaresTheCodecsItActuallyHas() {
        val declared: DeviceDecodeProfile = capabilities()

        assertTrue(declared.video.any { it.codec == DecodeCodec.H264 }, "no H264: ${declared.video}")
        assertTrue(declared.audio.isNotEmpty(), "no audio codecs at all")
        assertTrue(declared.containers.contains(DecodeContainer.HLS), declared.containers.toString())
    }

    /**
     * The per-codec bit depth, checked against the same thing a person checks
     * with `adb shell dumpsys media.player`.
     *
     * Without a libmpv payload the honest answer on every Android device
     * measured so far is codec-dependent, never a single flag ANDed across
     * every codec listed at all: this phone decodes HEVC Main 10 and does NOT
     * decode AVC High 10 (`profile/levels: [ 8/32768 (High/5.1) ]`, measured),
     * so H264's entry must claim 8-bit even while H265's claims 10, or a Hi10P
     * AVC file wins DirectPlay and draws nothing.
     */
    @Test
    fun maxBitDepthIsClaimedPerCodecOnlyWhenThisDeviceCanDecodeIt() {
        val declared: DeviceDecodeProfile = capabilities()
        val software: Boolean = MpvVideoEngineProvider.isAvailable()

        declared.video.forEach { capability ->
            val hardwareTenBit: Boolean = deviceSupportsTenBit(capability.codec)
            val expected: Int = if (software || hardwareTenBit) 10 else 8
            assertEquals(
                expected,
                capability.maxBitDepth,
                "codec=${capability.codec} maxBitDepth=${capability.maxBitDepth} " +
                    "libmpv=$software hardware10Bit=$hardwareTenBit",
            )
        }
    }

    /**
     * The per-codec profile list, checked the same way: an AVC entry that
     * never claims `high10`/`main10` is 8-bit-only H.264 on THIS device — the
     * exact bug case a flat allow-list plus one global boolean could not
     * express, and the Samsung phone that motivated this shape names it in
     * `high` only, never `high10`.
     */
    @Test
    fun profilesNameOnlyWhatThisDeviceActuallyDecodes() {
        val declared: DeviceDecodeProfile = capabilities()
        val software: Boolean = MpvVideoEngineProvider.isAvailable()

        declared.video.forEach { capability ->
            val claimsTenBitProfile = capability.profiles.any { it == "high10" || it == "main10" }
            assertEquals(
                software || deviceSupportsTenBit(capability.codec),
                claimsTenBitProfile,
                "codec=${capability.codec} profiles=${capability.profiles}",
            )
        }
    }

    // Zero here would tell the server this client cannot exceed zero kilobits,
    // and the server hard-transcodes above the cap it is given.
    @Test
    fun theResolutionIsOneOfTheThreeTheServerIsToldAbout() {
        val declared: DeviceDecodeProfile = capabilities()

        declared.video.forEach { capability ->
            assertTrue(
                capability.maxWidth in setOf(DecodeResolution.HD, DecodeResolution.FHD, DecodeResolution.UHD),
                "codec=${capability.codec} maxWidth=${capability.maxWidth}",
            )
        }
        assertEquals(0, declared.maxBitrateKbps, "a client-imposed bitrate cap was invented")
    }

    private fun deviceSupportsTenBit(codec: String): Boolean {
        val (mime, profile) = TEN_BIT_PROFILES[codec] ?: return false
        return deviceSupports(mime, profile)
    }

    private fun deviceSupports(mime: String, profile: Int): Boolean =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filterNot { info -> info.isEncoder }
            .filter { info -> info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .any { info ->
                info.getCapabilitiesForType(mime).profileLevels.any { level -> level.profile == profile }
            }

    private companion object {
        val TEN_BIT_PROFILES: Map<String, Pair<String, Int>> = mapOf(
            DecodeCodec.H265 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_HEVC to
                    android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                ),
            DecodeCodec.H264 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC to
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                ),
            DecodeCodec.AV1 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_AV1 to
                    android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                ),
        )
    }
}
