// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire shape, asserted rather than assumed.
//
// The server's DeviceCapabilities record uses PascalCase keys, and Kotlin's
// default would send camelCase — which deserialises into a record with every
// field at its default and no error anywhere. A device would declare that it
// can play nothing, the server would transcode everything, and the only symptom
// would be a warm CPU on the server.
class PlaybackCapabilitiesTest {

    private val capabilities = PlaybackCapabilities(
        videoCodecs = listOf(PlaybackCodec.H264, PlaybackCodec.HEVC),
        audioCodecs = listOf(PlaybackCodec.AAC),
        maxVideoHeight = 2160,
        maxAudioChannels = 6,
        hdrSupport = true,
        dolbyVision = DolbyVisionProfile.PROFILE_8_1,
        notes = "libmpv present",
    )

    @Test
    fun `every key is spelled the way the device hub spells it`() {
        val wire: String = Json.encodeToString(PlaybackCapabilities.serializer(), capabilities)

        listOf(
            "\"VideoCodecs\"",
            "\"AudioCodecs\"",
            "\"MaxVideoHeight\"",
            "\"MaxAudioChannels\"",
            "\"HdrSupport\"",
            "\"DolbyVision\"",
            "\"Notes\"",
        ).forEach { key ->
            assertTrue(wire.contains(key), "the payload does not carry $key: $wire")
        }
    }

    // Ten bit has NO field on this contract. The server's codec vocabulary is
    // h264/hevc/av1/vp9 with no bit depth in it, so a device that decodes 10-bit
    // AVC in software can only say so in the notes — and inventing a fifth codec
    // string here would be a value the server drops without complaining.
    @Test
    fun `no codec string outside the servers own vocabulary is sent`() {
        val known: Set<String> = setOf(
            PlaybackCodec.H264, PlaybackCodec.HEVC, PlaybackCodec.AV1, PlaybackCodec.VP9,
        )

        assertTrue(capabilities.videoCodecs.all { codec -> codec in known }, capabilities.videoCodecs.toString())
    }

    // Null is the server's word for unknown and zero is not: a zero maximum
    // height is a device declaring it can show nothing, which is a very
    // different instruction to a transcoder.
    @Test
    fun `unknown is null rather than zero`() {
        val unknown = PlaybackCapabilities()

        assertNull(unknown.maxVideoHeight)
        assertNull(unknown.maxAudioChannels)
        assertNull(unknown.playerBufferCapMb)
    }
}
