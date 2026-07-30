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

// The four answers the web's own walk gives, on the list that made them matter.
//
// A seek backwards is the case: the engine keeps what it had, so the ranges
// disagree with the playhead about where data is. Every one of these cases
// returns the last range's end if the walk is skipped, which is the shape of the
// bug — a bar promising buffer over a hole.
class BufferedFrontierTest {

    @Test
    fun insideARangeTheBufferStopsAtThatRangesEnd() {
        // Not 3600. The second range is on the far side of a hole and a bar
        // drawn to it invites a drag that stalls.
        assertEquals(90.0, bufferedFrontier(SPLIT, currentTime = 5.0))
    }

    @Test
    fun inTheHoleBetweenTwoRangesNothingIsBufferedAhead() {
        assertEquals(2000.0, bufferedFrontier(SPLIT, currentTime = 2000.0))
    }

    @Test
    fun beforeTheFirstRangeNothingIsBufferedAhead() {
        assertEquals(1.0, bufferedFrontier(LATE, currentTime = 1.0))
    }

    @Test
    fun pastEveryRangeTheBufferStopsWhereTheDataDoes() {
        // Ordinary at the end of an item: the engine reports a position past a
        // buffer it has stopped extending.
        assertEquals(3600.0, bufferedFrontier(SPLIT, currentTime = 3700.0))
    }

    @Test
    fun anEngineThatHasReadNothingBuffersNothing() {
        // Zero rather than the position. An engine with no ranges has not
        // buffered the frame it is showing either.
        assertEquals(0.0, bufferedFrontier(emptyList(), currentTime = 42.0))
    }

    @Test
    fun aSingleFrontierRangeAnswersTheSameWayItAlwaysDid() {
        // The shape TimeController synthesises for an engine that reports one
        // number. This has to keep answering that number, or every Media3 and
        // libVLC player loses its buffered bar.
        assertEquals(75.0, bufferedFrontier(listOf(TimeRange(0.0, 75.0)), currentTime = 30.0))
    }

    internal companion object {
        // Seek back an hour into a long film. The engine keeps what it had.
        val SPLIT: List<TimeRange> = listOf(
            TimeRange(0.0, 90.0),
            TimeRange(3500.0, 3600.0),
        )

        // A stream whose first data lands after the position the chrome asks about.
        val LATE: List<TimeRange> = listOf(
            TimeRange(100.0, 200.0),
        )
    }
}
