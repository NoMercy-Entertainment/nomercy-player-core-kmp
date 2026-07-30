// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.DynamicRange as MediaDynamicRange
import tv.nomercy.player.core.media.QualityDescriptor
import tv.nomercy.player.core.stream.MasterPlaylistRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Which rungs of Sintel's ladder a desktop is allowed to adapt into.
class VlcLadderNarrowingTest {

    private val ladder: List<QualityDescriptor> = MasterPlaylistRewriter.variants(SINTEL_MASTER)

    @Test
    fun theManifestBecomesRungsTheSharedDecisionsCanRead() {
        val levels: List<QualityLevel> = VlcLadderNarrowing.levelsOf(ladder)

        assertEquals(4, levels.size)
        assertEquals(
            2,
            levels.count { it.dynamicRange != DynamicRange.SDR },
            "PQ did not survive the trip into the ports type: $levels",
        )
    }

    @Test
    fun noWidthIsClaimedForARungThatDeclaredNone() {
        // The shared descriptor keeps only RESOLUTION's height. Inventing a width
        // here would make SizeAbrConstraint reject rungs the web keeps, and the port
        // would land on a different rendition than the oracle.
        val levels: List<QualityLevel> = VlcLadderNarrowing.levelsOf(ladder)

        assertTrue(levels.all { it.width == null }, "a width was invented: $levels")
    }

    @Test
    fun anSdrOnlyRequestDropsEveryHdrRungAndNotJustTheTallOnes() {
        // A filter on height alone would keep a SHORTER HDR rung, and adaptation
        // dropping into it is just as wrong as climbing above the ceiling.
        val keep: List<QualityDescriptor> =
            VlcLadderNarrowing.keep(ladder, emptyList(), maxHeight = 1635, sdrOnly = true)

        assertEquals(2, keep.size, "wrong number of rungs kept: $keep")
        assertTrue(
            keep.all { it.dynamicRange == MediaDynamicRange.Sdr },
            "an HDR rung survived an SDR-only request: $keep",
        )
    }

    @Test
    fun aSizeAndRangeConstraintTogetherLeaveOneRung() {
        // What the desktop actually computes for the testbed's pane on an SDR
        // screen: an 818 height ceiling and an SDR-only display.
        val keep: List<QualityDescriptor> =
            VlcLadderNarrowing.keep(ladder, emptyList(), maxHeight = 818, sdrOnly = true)

        assertEquals(1, keep.size, "wrong number of rungs kept: $keep")
        assertEquals(818, keep.single().height)
        assertEquals(MediaDynamicRange.Sdr, keep.single().dynamicRange)
    }

    @Test
    fun anHdrDisplayKeepsTheHdrRungsUnderTheSizeCeiling() {
        // The bug this signature exists to prevent. The size ceiling on this ladder
        // is whichever rung is cheapest at its height, which is the SDR one — so a
        // keep() that read the range off the ceiling capped an HDR display to SDR
        // using a constraint that was only ever asked about pixels.
        val keep: List<QualityDescriptor> =
            VlcLadderNarrowing.keep(ladder, emptyList(), maxHeight = 818, sdrOnly = false)

        assertEquals(2, keep.size, "the HDR rung was dropped on an HDR display: $keep")
        assertTrue(keep.any { it.dynamicRange != MediaDynamicRange.Sdr }, "no HDR rung survived: $keep")
    }

    @Test
    fun noConstraintKeepsTheWholeLadder() {
        assertEquals(ladder, VlcLadderNarrowing.keep(ladder, emptyList(), maxHeight = null, sdrOnly = false))
    }

    @Test
    fun aDeviceLadderNarrowsBeforeTheCeilingDoes() {
        // What the caller says the device can decode is a separate constraint, and
        // it has to survive alongside the other two rather than being replaced.
        val decodable: List<QualityDescriptor> = ladder.filter { it.height == 818 }

        val keep: List<QualityDescriptor> =
            VlcLadderNarrowing.keep(ladder, decodable, maxHeight = null, sdrOnly = false)

        assertEquals(decodable, keep)
    }

    @Test
    fun aComparisonThatKeepsNothingFallsBackRatherThanRefusing() {
        // An empty answer means the comparison was wrong, not that the item is
        // unplayable. Refusing playback is HdrPolicy's decision and it is taken
        // elsewhere; this must not take it by accident.
        assertEquals(
            ladder,
            VlcLadderNarrowing.keep(ladder, emptyList(), maxHeight = 1, sdrOnly = false),
        )
    }
}
