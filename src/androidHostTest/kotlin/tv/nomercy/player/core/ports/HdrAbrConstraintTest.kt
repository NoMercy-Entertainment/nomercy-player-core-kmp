// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// An interleaved ladder: HDR sits above SDR at the same height, which is what a
// real master produces and what makes "cap at the top rung" the wrong rule.
private val MIXED = listOf(
    QualityLevel(height = 1080, bitrate = 6_500_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
    QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
    QualityLevel(height = 720, bitrate = 3_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
)

// What adaptation may climb to on a given display.
//
// The failure this prevents is not a menu bug. Adaptation climbs on its own when
// the connection is good, so a viewer who never opened a quality menu gets a
// washed-out picture as a reward for good bandwidth — and nothing in the player
// reports anything wrong.
class HdrAbrConstraintTest {

    @Test
    fun anSdrDisplayCapsAtTheBestSdrRungRatherThanTheBestRung() {
        // The whole point. The top of this ladder is HDR 1080p; the cap has to
        // be the SDR 1080p below it, not the rung above and not the 720p.
        val ceiling: QualityLevel? = HdrAbrConstraint.abrCeiling(MIXED, displayHdr = false)

        assertEquals(DynamicRange.SDR, ceiling?.dynamicRange)
        assertEquals(1080, ceiling?.height)
    }

    @Test
    fun anHdrDisplayIsNotCapped() {
        assertNull(HdrAbrConstraint.abrCeiling(MIXED, displayHdr = true))
    }

    @Test
    fun aLadderWithNoHdrIsNotCappedEither() {
        // A cap that changes nothing still narrows what adaptation may pick if
        // anything about the comparison is subtly wrong. The safest constraint
        // is the one not applied.
        val sdrOnly: List<QualityLevel> = MIXED.filter { it.dynamicRange == DynamicRange.SDR }

        assertNull(HdrAbrConstraint.abrCeiling(sdrOnly, displayHdr = false))
    }

    @Test
    fun theCapPrefersHeightOverBitrate() {
        // A high-bitrate 720p is not a better cap than a 1080p, however many
        // bits it spends. This is the ordering a quality menu shows.
        val awkward = listOf(
            QualityLevel(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
            QualityLevel(height = 720, bitrate = 9_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
            QualityLevel(height = 1080, bitrate = 4_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
        )

        assertEquals(1080, HdrAbrConstraint.abrCeiling(awkward, displayHdr = false)?.height)
    }

    @Test
    fun anAllHdrLadderOnAnSdrDisplayIsReportedRatherThanCapped() {
        // There is nothing to cap to. The caller has to choose between a bad
        // picture and no picture, and that is a decision a library surfaces
        // rather than makes.
        val hdrOnly = listOf(
            QualityLevel(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
        )

        assertTrue(HdrAbrConstraint.isUnplayable(hdrOnly, displayHdr = false))
        assertNull(HdrAbrConstraint.abrCeiling(hdrOnly, displayHdr = false))
    }

    @Test
    fun anEmptyLadderIsNotUnplayable() {
        // The engine has not read the manifest yet, which is a moment a caller
        // can easily ask during. Reporting it as unplayable would show an error
        // for a stream that is about to work.
        assertFalse(HdrAbrConstraint.isUnplayable(emptyList(), displayHdr = false))
        assertNull(HdrAbrConstraint.abrCeiling(emptyList(), displayHdr = false))
    }

    @Test
    fun everyHdrFlavourCountsAsHdr() {
        // HLG and Dolby Vision are not HDR10 and are equally unshowable on an
        // SDR panel. Comparing against HDR10 alone would let the others through.
        listOf(DynamicRange.HDR10_PLUS, DynamicRange.DOLBY_VISION).forEach { range ->
            val ladder = listOf(
                QualityLevel(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = range),
                QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
            )

            assertEquals(1080, HdrAbrConstraint.abrCeiling(ladder, displayHdr = false)?.height, "$range slipped through")
        }
    }
}
