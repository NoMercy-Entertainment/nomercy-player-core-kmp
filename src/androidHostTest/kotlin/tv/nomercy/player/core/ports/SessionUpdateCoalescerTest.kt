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
import kotlin.test.assertTrue

// Android drops notification updates when a package posts more than about five
// a second ("Package enqueue rate ... Shedding", Xperia, 2026-10-23). Media3
// posts one per state change, so these pin how many reach it.
class SessionUpdateCoalescerTest {

    private class ManualTime : UpdateScheduler {
        var now = 0L
        private val due = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val canceled = BooleanArray(1)
            due += Triple(now + delayMs, task, canceled)
            return { canceled[0] = true }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = due.filter { !it.third[0] && it.first <= target }.minByOrNull { it.first } ?: break
                due.remove(next)
                now = next.first
                next.second()
            }
            now = target
        }
    }

    private val time = ManualTime()
    private var published = 0
    private var lastState = ""
    private val statesSeen = mutableListOf<String>()
    private val coalescer = UpdateCoalescer(WINDOW_MS, time) {
        published++
        statesSeen += lastState
    }

    private fun change(state: String) {
        lastState = state
        coalescer.request()
    }

    @Test
    fun aLoneUpdateReachesMedia3AtOnce() {
        change("playing")

        assertEquals(1, published, "an idle session must not wait for company")
        assertEquals(0L, time.now)
    }

    @Test
    fun aBurstOfEightInAHundredMillisecondsPublishesTheFirstAndTheFinalState() {
        repeat(8) { index ->
            change("state-$index")
            time.advance(12)
        }
        time.advance(WINDOW_MS)

        assertEquals(listOf("state-0", "state-7"), statesSeen)
    }

    @Test
    fun theFinalStateOfABurstIsNeverDelayedPastTheWindow() {
        change("first")
        time.advance(10)
        change("last")
        time.advance(WINDOW_MS - 10 - 1)
        assertEquals(1, published, "the held update is not due yet")

        time.advance(1)

        assertEquals(listOf("first", "last"), statesSeen)
    }

    @Test
    fun sustainedChangesNeverPublishMoreThanOncePerWindow() {
        repeat(40) { index ->
            change("state-$index")
            time.advance(25)
        }
        time.advance(WINDOW_MS)

        // 1000 ms of changes: the window caps it at one publish per 250 ms
        // plus the leading one, well under the five a second Android sheds at.
        assertTrue(published <= 1_000 / WINDOW_MS + 1, "published $published times in a second")
    }

    @Test
    fun aPlayStartPostsAtMostThreeUpdates() {
        // The bridge requests, in order: item metadata, playing, first-tick
        // duration, then a resume seek reported twice (Seek and Seeked).
        listOf(0L, 15L, 30L, 80L, 95L, 110L).forEachIndexed { index, at ->
            time.advance(at - time.now)
            change("request-$index")
        }
        time.advance(WINDOW_MS * 2)

        assertTrue(published <= 3, "a play start published $published times")
        assertEquals("request-5", statesSeen.last())
    }

    @Test
    fun anIdleWindowThatSawNothingPublishesNothingMore() {
        change("playing")
        time.advance(WINDOW_MS * 4)

        assertEquals(1, published)
    }

    @Test
    fun aChangeAfterQuietGoesStraightThrough() {
        change("one")
        time.advance(WINDOW_MS * 2)
        change("two")

        assertEquals(listOf("one", "two"), statesSeen)
    }

    @Test
    fun aZeroWindowPublishesEveryRequest() {
        var count = 0
        val immediate = UpdateCoalescer(0L, time) { count++ }

        repeat(5) { immediate.request() }

        assertEquals(5, count)
    }

    @Test
    fun cancelDropsTheHeldUpdate() {
        change("one")
        change("two")
        coalescer.cancel()
        time.advance(WINDOW_MS * 2)

        assertEquals(listOf("one"), statesSeen)
    }

    private class Clock(var now: Long = 0L)

    private val clock = Clock()
    private val progress = PlaybackProgress { clock.now }

    @Test
    fun aTickWhilePlayingIsNotAChange() {
        assertTrue(progress.report(playing = true, positionMs = 0))

        clock.now = 5_000
        assertFalse(progress.report(playing = true, positionMs = 5_000), "Media3 extrapolates a playing position")
    }

    @Test
    fun aRepeatedPauseAtTheSamePositionIsNotAChange() {
        assertTrue(progress.report(playing = false, positionMs = 42_000))

        assertFalse(progress.report(playing = false, positionMs = 42_000))
    }

    @Test
    fun aSeekWhilePlayingIsAChange() {
        progress.report(playing = true, positionMs = 0)

        clock.now = 5_000
        assertTrue(progress.report(playing = true, positionMs = 90_000))
    }

    @Test
    fun aSeekWhilePausedIsAChange() {
        progress.report(playing = false, positionMs = 10_000)

        assertTrue(progress.report(playing = false, positionMs = 70_000))
    }

    @Test
    fun pausingAndResumingAreChanges() {
        progress.report(playing = true, positionMs = 0)

        assertTrue(progress.report(playing = false, positionMs = 3_000))
        assertTrue(progress.report(playing = true, positionMs = 3_000))
    }

    @Test
    fun aStateBuiltAfterASkippedTickStillReadsTheExtrapolatedPosition() {
        progress.report(playing = true, positionMs = 10_000)
        clock.now = 4_000
        progress.report(playing = true, positionMs = 14_000)

        assertEquals(14_000L, progress.currentPositionMs())
        clock.now = 9_000
        assertEquals(19_000L, progress.currentPositionMs(), "playing time since the last shown position counts")
    }

    @Test
    fun aPausedPositionDoesNotAdvance() {
        progress.report(playing = false, positionMs = 10_000)
        clock.now = 9_000

        assertEquals(10_000L, progress.currentPositionMs())
    }

    @Test
    fun resetForgetsWhatWasShown() {
        progress.report(playing = true, positionMs = 30_000)
        progress.reset()

        assertTrue(progress.report(playing = true, positionMs = 30_000))
    }

    private companion object {
        const val WINDOW_MS = 250L
    }
}
