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
 * guards is a client declaring 10-bit support it does not have, which the
 * server believes, and which reaches the viewer as a black picture with no
 * error anywhere in the chain.
 */
class ClientCapabilitiesDeviceTest {

    private fun capabilities(): ClientCapabilities {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PlatformEnvironment.install(PlatformContext(context.applicationContext))
        return platformClientCapabilities().also { probed ->
            // Logged, because a passing assertion says the relationship holds and
            // says nothing about what this device actually answered — and what it
            // answered is the thing anybody debugging a transcode decision needs.
            android.util.Log.i("NoMercyCaps", probed.toJson())
        }
    }

    // Every device decodes AVC. A client that cannot say so would have the
    // server transcode every file in the library.
    @Test
    fun theDeviceDeclaresTheCodecsItActuallyHas() {
        val declared: ClientCapabilities = capabilities()

        assertTrue(ClientCodec.H264 in declared.videoCodecs, "no H264: ${declared.videoCodecs}")
        assertTrue(declared.audioCodecs.isNotEmpty(), "no audio codecs at all")
        assertTrue(declared.containers.contains(ClientContainer.HLS), declared.containers.toString())
    }

    /**
     * The claim, checked against the same thing a person checks with
     * `adb shell dumpsys media.player`.
     *
     * Without a libmpv payload the honest answer on every Android device
     * measured so far is false, and false is what makes the server transcode
     * instead of the phone drawing black. With one it is true, because ffmpeg
     * opens the file rather than the chip. Asserting the RELATIONSHIP rather
     * than a constant means this test still holds on the day a device ships
     * that does High 10 in hardware.
     */
    @Test
    fun tenBitIsClaimedOnlyWhenSomethingOnThisDeviceCanDecodeIt() {
        val declared: ClientCapabilities = capabilities()
        val software: Boolean = MpvVideoEngineProvider.isAvailable()
        // EVERY declared codec, because the server's flag is one flag for all
        // of them: `bitDepthOk = video.BitDepth <= 8 || client.Supports10Bit`.
        // A device that does 10-bit HEVC and not 10-bit AVC must answer no, or
        // a Hi10P AVC file wins DirectPlay and draws nothing.
        val hardware: Boolean = declared.videoCodecs.isNotEmpty() &&
            declared.videoCodecs.all { codec -> deviceSupportsTenBit(codec) }

        assertEquals(
            software || hardware,
            declared.supports10Bit,
            "supports_10bit=${declared.supports10Bit} with libmpv=$software and hardware 10-bit=$hardware",
        )
    }

    // Zero here would tell the server this client cannot exceed zero kilobits,
    // and the server hard-transcodes above the cap it is given.
    @Test
    fun theResolutionIsOneOfTheThreeTheServerIsToldAbout() {
        val declared: ClientCapabilities = capabilities()

        assertTrue(
            declared.maxWidth in setOf(ClientResolution.HD, ClientResolution.FHD, ClientResolution.UHD),
            "max_width=${declared.maxWidth}",
        )
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
            ClientCodec.H265 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_HEVC to
                    android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                ),
            ClientCodec.H264 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC to
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                ),
            ClientCodec.AV1 to (
                android.media.MediaFormat.MIMETYPE_VIDEO_AV1 to
                    android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                ),
        )
    }
}
