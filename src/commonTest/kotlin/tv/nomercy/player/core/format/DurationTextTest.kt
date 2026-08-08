// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The number under a progress bar.
 *
 * The cases are the web's `__tests__/format.test.ts` plus the two the video
 * package's own test had, carried over when this moved into core.
 */
class DurationTextTest {

    @Test
    fun theStartOfAFileIsZero() {
        assertEquals("0:00", formatSeconds(0.0))
    }

    @Test
    fun subMinuteValuesKeepAZeroMinutesField() {
        assertEquals("0:05", formatSeconds(5.0))
        assertEquals("0:59", formatSeconds(59.0))
    }

    @Test
    fun secondsRollIntoMinutesAtSixty() {
        assertEquals("1:00", formatSeconds(60.0))
        assertEquals("1:01", formatSeconds(61.0))
    }

    @Test
    fun theLeadingMinutesFieldIsNotPadded() {
        assertEquals("1:05", formatSeconds(65.0))
        assertEquals("10:00", formatSeconds(600.0))
    }

    @Test
    fun theShortFormHoldsRightUpToTheHourBoundary() {
        assertEquals("59:59", formatSeconds(3599.0))
    }

    @Test
    fun minutesRollIntoHoursAtThirtySixHundred() {
        assertEquals("1:00:00", formatSeconds(3600.0))
    }

    @Test
    fun aShortEpisodeDoesNotCarryAnHoursField() {
        // 0:20:00 makes somebody count the fields to work out which is which.
        assertEquals("20:00", formatSeconds(1200.0))
    }

    @Test
    fun aFilmDoes() {
        assertEquals("1:01:05", formatSeconds(3665.0))
    }

    @Test
    fun minutesKeepTwoDigitsOnceThereIsAnHour() {
        assertEquals("2:05:00", formatSeconds(7500.0))
    }

    @Test
    fun aNegativeArrivesFromASubtractionAndIsNotShownAsOne() {
        // Remaining time crosses zero at the end of a file, and a bar reading
        // "-0:-1" gets reported as a playback bug rather than a formatting one.
        assertEquals("0:00", formatSeconds(-3.0))
    }

    /**
     * A live stream's duration genuinely is infinite.
     *
     * Kotlin saturates the conversion rather than overflowing it, so the naive
     * version rendered this as "596523:14:07" — which reads as a corrupted file
     * rather than as a stream without an end.
     */
    @Test
    fun anInfiniteDurationIsZeroRatherThanSixHundredThousandHours() {
        assertEquals("0:00", formatSeconds(Double.POSITIVE_INFINITY))
        assertEquals("0:00", formatSeconds(Double.NaN))
    }

    @Test
    fun fractionsOfASecondAreDroppedRatherThanRounded() {
        // A position ticking to 1:00 before the second has elapsed shows a bar
        // ahead of the picture, which is the one place it is visibly wrong.
        assertEquals("0:59", formatSeconds(59.99))
    }

    @Test
    fun aLabelForNothingIsEmptyRatherThanZero() {
        // A playlist claiming every unknown-length track is zero seconds long
        // reads as a library that failed to scan.
        assertEquals("", formatDuration(null as Double?))
        assertEquals("", formatDuration(0.0))
        assertEquals("", formatDuration(-1.0))
        assertEquals("", formatDuration(Double.POSITIVE_INFINITY))
    }

    @Test
    fun aLabelForSecondsIsTheSameFormatTheBarUses() {
        assertEquals("24:14", formatDuration(1454.0))
    }

    @Test
    fun theWiresLeadingZeroHourIsDropped() {
        // "00:24:14" beside a list of tracks written "24:14" makes one row look
        // like a different unit.
        assertEquals("24:14", formatDuration("00:24:14"))
    }

    @Test
    fun aWireDurationThatHasRealHoursKeepsThem() {
        assertEquals("01:24:14", formatDuration("01:24:14"))
    }

    @Test
    fun anAbsentWireDurationIsEmpty() {
        assertEquals("", formatDuration(null as String?))
    }
}
