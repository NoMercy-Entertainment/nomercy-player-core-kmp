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

class AudioProbeTableTest {

    private val decodable: List<Pair<String, String>> = listOf(
        "audio/mp4a-latm" to DecodeCodec.AAC,
        "audio/ac3" to DecodeCodec.AC3,
    )

    @Test
    fun aPassthroughOnlyCodecIsProbedAlongsideTheDecodableOnes() {
        val probes = audioProbes(decodable, setOf(DecodeCodec.AC3, DecodeCodec.DTS, DecodeCodec.TRUEHD))

        assertTrue(probes.any { it.second == DecodeCodec.DTS })
        assertTrue(probes.any { it.second == DecodeCodec.TRUEHD })
    }

    @Test
    fun aCodecInBothTablesIsProbedOnce() {
        val probes = audioProbes(decodable, setOf(DecodeCodec.AC3))

        assertEquals(1, probes.count { it.second == DecodeCodec.AC3 })
    }

    @Test
    fun theDecodableOrderIsKept() {
        val probes = audioProbes(decodable, setOf(DecodeCodec.DTS))

        assertEquals(decodable, probes.take(decodable.size))
    }
}
