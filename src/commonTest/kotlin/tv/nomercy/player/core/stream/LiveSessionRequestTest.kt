// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Asserted on the serialised form, because that is what crosses the wire. A test
// that checked the Kotlin object would pass while the server rejected every
// request.
class LiveSessionRequestTest {

    private val noAv1 = ClientCapabilities(
        videoCodecs = listOf(ServerCodecNames.H264, ServerCodecNames.H265),
        audioCodecs = listOf(ServerCodecNames.AAC, ServerCodecNames.OPUS),
        containers = listOf("mp4", "ts"),
        maxWidth = 1920,
        maxHeight = 1080,
        supportsHdr = false,
        supports10Bit = false,
        maxBitrateKbps = 12_000,
    )

    @Test
    fun theFieldNamesAreTheServersOwn() {
        val json = LiveSessionRequest("file-1", noAv1, startTimeSeconds = 42.5).toJson()

        // From LiveTranscodeController.StartSession and ClientCapabilitiesDto.
        // Renaming any of these on either side has to be a visible change on
        // both, which is what this test is for.
        assertTrue(json.contains("\"video_file_id\":\"file-1\""))
        assertTrue(json.contains("\"client_caps\""))
        assertTrue(json.contains("\"start_time_seconds\":42.5"))
        assertTrue(json.contains("\"max_bitrate_kbps\":12000"))
        assertTrue(json.contains("\"supports_10bit\":false"))
        assertTrue(json.contains("\"max_audio_channels\":2"))
    }

    @Test
    fun aDeviceWithNoAv1ProducesARequestTheServerCanOnlyAnswerWithoutIt() {
        val json = LiveSessionRequest("file-1", noAv1).toJson()

        assertTrue(json.contains("\"video_codecs\":[\"H264\",\"H265\"]"))
        assertFalse(json.contains("Av1"))
    }

    @Test
    fun codecsTravelAsNamesNotOrdinals() {
        val json = LiveSessionRequest("file-1", noAv1).toJson()

        // The server deserialises either, and a name survives someone adding a
        // codec to the middle of the enum where an ordinal quietly becomes a
        // different codec.
        assertFalse(json.contains("\"video_codecs\":[0"))
        assertTrue(json.contains("\"H264\""))
    }

    @Test
    fun noPreferenceMeansNoFieldRatherThanAFieldAskingForNull() {
        val json = LiveSessionRequest("file-1", noAv1).toJson()

        // A server that has never heard of these fields must still get a
        // request it can serve. Absent has to be the safe case, or every server
        // upgrade breaks a client.
        assertFalse(json.contains("preferred_quality"))
        assertFalse(json.contains("audio_language"))
    }

    @Test
    fun aPreferenceThatWasGivenIsSent() {
        val json = LiveSessionRequest(
            "file-1",
            noAv1,
            preferredQuality = "1080p",
            audioLanguage = "nld",
        ).toJson()

        assertTrue(json.contains("\"preferred_quality\":\"1080p\""))
        assertTrue(json.contains("\"audio_language\":\"nld\""))
    }

    @Test
    fun aStartTimeOfZeroIsStillSentSoTheServerDoesNotHaveToGuess() {
        val json = LiveSessionRequest("file-1", noAv1).toJson()

        assertTrue(json.contains("\"start_time_seconds\":0.0"))
    }

    @Test
    fun anHdrCapableDeviceSaysSoOnBothFieldsTheServerReads() {
        val hdr = noAv1.copy(supportsHdr = true, supports10Bit = true, maxHeight = 2160, maxWidth = 3840)
        val json = LiveSessionRequest("file-1", hdr).toJson()

        // Ten-bit and HDR are separate questions: a device can decode ten-bit
        // SDR, and a panel can be HDR while the decoder is not.
        assertTrue(json.contains("\"supports_hdr\":true"))
        assertTrue(json.contains("\"supports_10bit\":true"))
        assertTrue(json.contains("\"max_height\":2160"))
    }

    @Test
    fun surroundCapabilityIsStatedRatherThanAssumed() {
        val surround = noAv1.copy(maxAudioChannels = 6)
        val json = LiveSessionRequest("file-1", surround).toJson()

        assertEquals(true, json.contains("\"max_audio_channels\":6"))
    }
}
