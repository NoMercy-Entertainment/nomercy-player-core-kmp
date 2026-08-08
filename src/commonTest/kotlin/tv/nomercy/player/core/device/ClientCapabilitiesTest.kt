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
import kotlin.test.assertTrue

// The wire shape, asserted rather than assumed.
//
// The server reads `client_caps` in snake_case through Newtonsoft. Kotlin's
// default is the property name, and a mismatch deserialises into a DTO with
// every field at its default — no error anywhere, the client silently declares
// it can play nothing, and the server transcodes everything. The only symptom
// is a warm CPU on somebody's server.
class ClientCapabilitiesTest {

    private val capabilities = ClientCapabilities(
        videoCodecs = listOf(ClientCodec.H264, ClientCodec.H265),
        audioCodecs = listOf(ClientCodec.AAC),
        containers = listOf(ClientContainer.HLS),
        maxWidth = 3840,
        maxHeight = 2160,
        supportsHdr = true,
        supports10Bit = true,
        maxAudioChannels = 6,
        maxBitrateKbps = 0,
    )

    @Test
    fun `every key is spelled the way the server reads it`() {
        val wire: String = capabilities.toJson()

        listOf(
            "\"video_codecs\"",
            "\"audio_codecs\"",
            "\"containers\"",
            "\"max_width\"",
            "\"max_height\"",
            "\"supports_hdr\"",
            "\"supports_10bit\"",
            "\"max_audio_channels\"",
            "\"max_bitrate_kbps\"",
        ).forEach { key ->
            assertTrue(wire.contains(key), "the payload does not carry $key: $wire")
        }
    }

    // PascalCase, because the server's VideoCodecType deserialises through a
    // StringEnumConverter. Lowercase is the DEVICE HUB's vocabulary, which is a
    // different contract, and mixing them sends names one of the two drops.
    @Test
    fun `codec names are the encoder enums spelling`() {
        val wire: String = capabilities.toJson()

        assertTrue(wire.contains("\"H264\""), wire)
        assertTrue(wire.contains("\"H265\""), wire)
    }

    // Zero is "no client-imposed cap", not "cannot exceed zero". The server
    // skips its bitrate gate at or below zero, and a throttled estimate here
    // forces live transcoding of compatible files over a LAN.
    @Test
    fun `an unprobed client imposes no bitrate cap and claims nothing`() {
        val blank = ClientCapabilities()

        assertEquals(0, blank.maxBitrateKbps)
        assertEquals(2, blank.maxAudioChannels)
        assertTrue(blank.videoCodecs.isEmpty())
        assertTrue(!blank.supports10Bit, "an unprobed client must not claim 10-bit decoding")
    }

    // The three the web probe clamps to. A client reporting its exact panel
    // size gets a ladder built for that one panel, and the same television
    // through two clients has to get one decision.
    @Test
    fun `resolutions clamp to the same three web clamps to`() {
        assertEquals(ClientResolution.UHD, ClientResolution.clamp(5120))
        assertEquals(ClientResolution.UHD, ClientResolution.clamp(3840))
        assertEquals(ClientResolution.FHD, ClientResolution.clamp(2560))
        assertEquals(ClientResolution.FHD, ClientResolution.clamp(1920))
        assertEquals(ClientResolution.HD, ClientResolution.clamp(1366))
    }
}
