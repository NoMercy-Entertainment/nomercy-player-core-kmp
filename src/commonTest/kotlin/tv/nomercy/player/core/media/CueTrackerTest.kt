// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import tv.nomercy.player.core.events.CueEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueTrackerTest {

    private fun cue(start: Double, end: Double, text: String) =
        CueEvent(id = text, startTime = start, endTime = end, text = text)

    @Test
    fun aPositionInsideACueEntersIt() {
        val tracker = CueTracker(listOf(cue(0.0, 2.0, "one")))

        val crossing = tracker.advanceTo(1.0)

        assertEquals(listOf("one"), crossing.entered.map { it.text })
        assertEquals("one", tracker.line?.text)
    }

    // A tracker that reported the same line on every time update would make a
    // consumer dedupe what it already knows.
    @Test
    fun stayingInsideTheSameCueReportsNothing() {
        val tracker = CueTracker(listOf(cue(0.0, 5.0, "one")))
        tracker.advanceTo(1.0)

        val crossing = tracker.advanceTo(2.0)

        assertTrue(!crossing.changed, "expected no crossing, got $crossing")
        assertEquals("one", tracker.line?.text)
    }

    // A seek can leave one cue and enter another at the same instant, and two
    // separate queries would let a consumer draw the gap between them.
    @Test
    fun leavingOneAndEnteringAnotherIsOneCrossing() {
        val tracker = CueTracker(listOf(cue(0.0, 2.0, "one"), cue(2.0, 4.0, "two")))
        tracker.advanceTo(1.0)

        val crossing = tracker.advanceTo(3.0)

        assertEquals(listOf("one"), crossing.exited.map { it.text })
        assertEquals(listOf("two"), crossing.entered.map { it.text })
    }

    // Closed at both ends is the classic way to get a flicker between lines.
    @Test
    fun aCueEndingWhereTheNextBeginsDoesNotOverlap() {
        val tracker = CueTracker(listOf(cue(0.0, 2.0, "one"), cue(2.0, 4.0, "two")))

        tracker.advanceTo(2.0)

        assertEquals(listOf("two"), tracker.active.map { it.text })
    }

    @Test
    fun aPositionPastTheEndExitsWhateverWasShowing() {
        val tracker = CueTracker(listOf(cue(0.0, 2.0, "one")))
        tracker.advanceTo(1.0)

        val crossing = tracker.advanceTo(9.0)

        assertEquals(listOf("one"), crossing.exited.map { it.text })
        assertNull(tracker.line)
    }

    // A parser is not required to emit in order, and a caller that had to sort
    // first would be a caller who forgets to.
    @Test
    fun cuesOutOfOrderStillReadInOrder() {
        val tracker = CueTracker(listOf(cue(4.0, 6.0, "late"), cue(0.0, 2.0, "early")))

        assertEquals(listOf("early", "late"), tracker.cues().map { it.text })
    }

    // Keeping the old set would leave a line from the previous track marked
    // active and never report it leaving.
    @Test
    fun loadingNewCuesForgetsWhereWeWere() {
        val tracker = CueTracker(listOf(cue(0.0, 5.0, "old")))
        tracker.advanceTo(1.0)

        tracker.load(listOf(cue(0.0, 5.0, "new")))
        val crossing = tracker.advanceTo(1.0)

        assertEquals(listOf("new"), crossing.entered.map { it.text })
        assertTrue(crossing.exited.isEmpty(), "the old cue is gone, not exited")
    }

    @Test
    fun overlappingCuesAreBothActive() {
        val tracker = CueTracker(listOf(cue(0.0, 4.0, "under"), cue(1.0, 2.0, "over")))

        tracker.advanceTo(1.5)

        assertEquals(listOf("under", "over"), tracker.active.map { it.text })
    }
}
