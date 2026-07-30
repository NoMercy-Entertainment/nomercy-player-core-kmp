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
import kotlin.test.assertNull

// Capping the ladder to the space the picture is drawn in.
//
// The ladder is Sintel's real one, read from the shipped manifest, because the
// defect was measured on it: a pane 800 by 372 device-pixels was being handed
// 3840x1635 nine seconds in, and delivery sat at 6.5 frames a second against a
// 24 frame clip.
class SizeAbrConstraintTest {

    @Test
    fun aSmallPaneCapsBelowTheFourKRungs() {
        val ceiling: QualityLevel? = SizeAbrConstraint.abrCeiling(SINTEL, PANE_WIDTH, PANE_HEIGHT)

        // The smallest rung that still covers the pane, which is 818 and not 1635.
        // This is the whole defect: without it adaptation climbs to 3840 for a box
        // that can show 372 rows.
        assertEquals(SDR_1080, ceiling)
    }

    @Test
    fun aLadderOfOneHeightHasNoSizeCeiling() {
        // Two renditions at 818 and nothing else. Bitrate breaks the tie downward —
        // the first test proves that on the full ladder — but here the winner is
        // also the top of the ladder, so there is nothing for a SIZE constraint to
        // say. Which of the two plays is the dynamic-range decision's business, and
        // answering it from here would cap an HDR display to an SDR rendition.
        val ceiling: QualityLevel? = SizeAbrConstraint.abrCeiling(
            listOf(HDR_1080, SDR_1080),
            PANE_WIDTH,
            PANE_HEIGHT,
        )

        assertNull(ceiling)
    }

    @Test
    fun anUnmeasuredPaneCapsNothing() {
        // Zero is what a surface reports before its first layout pass. Treating it
        // as a size would cap to the bottom of the ladder on every cold start.
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, 0, 0))
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, PANE_WIDTH, 0))
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, 0, PANE_HEIGHT))
    }

    @Test
    fun aPaneBiggerThanTheLadderCapsNothing() {
        // Nothing to cap to: every rung is already smaller than the space. A cap
        // that changes nothing still narrows what adaptation may pick if anything
        // about the comparison is subtly wrong.
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, FOUR_K_WIDTH, FOUR_K_HEIGHT))
    }

    @Test
    fun aPaneThatOnlyTheTallestRungCoversCapsNothing() {
        // A ceiling at the top of the ladder is not a ceiling. This is the guard the
        // web adapter added, mirrored here — without it the port caps where the
        // oracle does not, and the two land on different renditions for the same
        // pane.
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, 1920, 1000))
        assertNull(SizeAbrConstraint.abrCeiling(SINTEL, 3840, 1635))
    }

    @Test
    fun anUndeclaredWidthIsNotANarrowWidth() {
        // The desktop ladder comes from a descriptor that carries no width at all,
        // and reading that absence as zero would drop every rung. Falling back to
        // the height is worse because it looks reasonable: on this wide pane a
        // 1920x818 rung would read as 818 wide, fail, and hand over 4K.
        val widthless: List<QualityLevel> = SINTEL.map { it.copy(width = null) }

        val ceiling: QualityLevel? = SizeAbrConstraint.abrCeiling(widthless, WIDE_PANE_WIDTH, WIDE_PANE_HEIGHT)

        assertEquals(818, ceiling?.height)
    }

    @Test
    fun aDeclaredWidthTooNarrowForThePaneIsRejected() {
        // The other half of the same rule: a width that IS declared and does not
        // reach across the pane is not a rung that covers it.
        val ceiling: QualityLevel? = SizeAbrConstraint.abrCeiling(
            listOf(SDR_1080.copy(width = 640)),
            WIDE_PANE_WIDTH,
            WIDE_PANE_HEIGHT,
        )

        assertNull(ceiling)
    }

    @Test
    fun theNarrowerOfTwoCeilingsWins() {
        // Two constraints exist the moment a display's dynamic range and a pane's
        // size both have something to say, and neither may overwrite the other.
        assertEquals(SDR_1080, SizeAbrConstraint.narrower(SDR_2160, SDR_1080))
        assertEquals(SDR_1080, SizeAbrConstraint.narrower(SDR_1080, SDR_2160))
    }

    @Test
    fun theCheaperCeilingWinsAtTheSameHeight() {
        assertEquals(SDR_1080, SizeAbrConstraint.narrower(HDR_1080, SDR_1080))
    }

    @Test
    fun oneCeilingIsTheAnswerWhenTheOtherIsAbsent() {
        assertEquals(SDR_1080, SizeAbrConstraint.narrower(null, SDR_1080))
        assertEquals(SDR_1080, SizeAbrConstraint.narrower(SDR_1080, null))
        assertNull(SizeAbrConstraint.narrower(null, null))
    }
}

// Sintel's shipped ladder, attribute for attribute.
private val SDR_1080 = QualityLevel(
    height = 818,
    bitrate = 743_922,
    codec = "avc1",
    dynamicRange = DynamicRange.SDR,
    width = 1920,
)
private val HDR_1080 = QualityLevel(
    height = 818,
    bitrate = 821_147,
    codec = "avc1",
    dynamicRange = DynamicRange.HDR10,
    width = 1920,
)
private val SDR_2160 = QualityLevel(
    height = 1635,
    bitrate = 2_077_179,
    codec = "avc1",
    dynamicRange = DynamicRange.SDR,
    width = 3840,
)
private val HDR_2160 = QualityLevel(
    height = 1635,
    bitrate = 2_402_870,
    codec = "avc1",
    dynamicRange = DynamicRange.HDR10,
    width = 3840,
)

private val SINTEL = listOf(SDR_1080, HDR_1080, SDR_2160, HDR_2160)

// The testbed's own pane at the default window size.
private const val PANE_WIDTH = 800
private const val PANE_HEIGHT = 372

private const val WIDE_PANE_WIDTH = 1200
private const val WIDE_PANE_HEIGHT = 300

private const val FOUR_K_WIDTH = 3840
private const val FOUR_K_HEIGHT = 2160
