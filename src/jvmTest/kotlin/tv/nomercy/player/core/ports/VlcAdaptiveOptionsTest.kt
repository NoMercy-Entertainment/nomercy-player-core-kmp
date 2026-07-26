// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.DynamicRange
import tv.nomercy.player.core.media.QualityDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// How the desktop narrows a ladder.
//
// Android rewrites the manifest because Media3 has no other way to be told which
// variants exist. libVLC takes options, so the constraint is handed to the engine
// instead of hidden from it — and the numbers have to be in the units libVLC
// expects or the constraint silently does nothing.
class VlcAdaptiveOptionsTest {

    private val ladder = listOf(
        QualityDescriptor(height = 1080, bitrate = 6_000_000, codec = "avc1"),
        QualityDescriptor(height = 720, bitrate = 3_000_000, codec = "avc1"),
    )

    @Test
    fun theLimitsAreTheTopOfWhatIsAllowed() {
        val options: List<String> = VlcAdaptiveOptions.optionsFor(ladder)

        assertTrue(options.contains(":adaptive-maxheight=1080"), "wrong height limit: $options")
    }

    @Test
    fun bandwidthIsInKilobitsBecauseThatIsWhatLibVlcReads() {
        // Passing bits reads as a connection a thousand times faster than any
        // real one, which is the same as no constraint at all — and it fails
        // silently, because libVLC accepts the number.
        val options: List<String> = VlcAdaptiveOptions.optionsFor(ladder)

        assertTrue(options.contains(":adaptive-bw=6000"), "bandwidth not in kilobits: $options")
    }

    @Test
    fun anEmptyLadderConstrainsNothing() {
        // An option that repeats the default still sends libVLC down a code path
        // it would not otherwise take, and on a demuxer this old the untaken path
        // is the tested one.
        assertEquals(emptyList(), VlcAdaptiveOptions.optionsFor(emptyList()))
    }

    @Test
    fun theLimitFollowsTheTallestRungRatherThanTheFirst() {
        // A ladder is not always sorted, and taking the first rung's height would
        // cap at whatever order the manifest happened to use.
        val unsorted = listOf(
            QualityDescriptor(height = 720, bitrate = 3_000_000, codec = "avc1"),
            QualityDescriptor(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = DynamicRange.Hdr10),
        )

        val options: List<String> = VlcAdaptiveOptions.optionsFor(unsorted)

        assertTrue(options.contains(":adaptive-maxheight=2160"), "capped at the wrong rung: $options")
    }
}
