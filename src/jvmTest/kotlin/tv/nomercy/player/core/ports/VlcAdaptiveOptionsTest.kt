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
    fun bandwidthIsNotPassedBecauseLibVlcDoesNotReadItHere() {
        // This asserted the opposite, and the assertion was wrong about libVLC
        // rather than about the code. Measured against the installed VLC 3.0.23:
        // --adaptive-bw=800 against a ladder of 743922 and 821147 bps switched onto
        // the 821 kbps rendition anyway. It is the FIXED-RATE logic's assumed
        // bandwidth, ignored by the default logic, and documented in KiB/s — so the
        // value was in the wrong unit for an option that was not being read.
        //
        // An option that silently does nothing is worse than no option, because it
        // reads as a constraint that is in place.
        val options: List<String> = VlcAdaptiveOptions.optionsFor(ladder)

        assertTrue(options.none { it.startsWith(":adaptive-bw") }, "an inert option came back: $options")
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
