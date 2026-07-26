// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val UNTITLED = "Untitled"

private fun filler(): String = UNTITLED

class FillChapterGapsTest {

    private fun chapter(start: Double, title: String = "c$start"): Chapter =
        Chapter(startTime = start, title = title)

    @Test
    fun anItemWithNoChaptersStaysThatWay() {
        // Not a hole — an item without chapters. Inventing one whole-length
        // chapter would make a chrome draw a chapter bar for a film that has
        // none.
        val result: ChapterGapFill = fillChapterGaps(emptyList(), 100.0, ::filler)

        assertFalse(result.changed)
        assertTrue(result.chapters.isEmpty())
    }

    @Test
    fun anUnknownDurationChangesNothing() {
        // A live stream, or metadata that has not arrived. The end is not
        // somewhere to fill up to.
        val one: List<Chapter> = listOf(chapter(0.0))

        assertFalse(fillChapterGaps(one, 0.0, ::filler).changed)
        assertFalse(fillChapterGaps(one, Double.NaN, ::filler).changed)
        assertFalse(fillChapterGaps(one, Double.POSITIVE_INFINITY, ::filler).changed)
    }

    @Test
    fun aFirstChapterStartingPartwayInGetsAFillerBeforeIt() {
        // An opening credits sequence nobody labelled. Without this a viewer
        // scrubbing into the first thirty seconds is inside no chapter at all,
        // and a chrome showing the current chapter title shows nothing.
        val result: ChapterGapFill = fillChapterGaps(listOf(chapter(30.0)), 100.0, ::filler)

        assertTrue(result.changed)
        assertEquals(2, result.chapters.size)
        assertEquals(0.0, result.chapters.first().startTime)
        assertTrue(result.chapters.first().synthetic)
        assertEquals(UNTITLED, result.chapters.first().title)
    }

    @Test
    fun aListThatAlreadyStartsAtZeroIsLeftAlone() {
        val chapters: List<Chapter> = listOf(chapter(0.0), chapter(60.0))

        val result: ChapterGapFill = fillChapterGaps(chapters, 100.0, ::filler)

        assertFalse(result.changed)
        assertEquals(chapters, result.chapters)
    }

    @Test
    fun aStartWithinTheEpsilonCountsAsZero() {
        // A tenth of a second is a rounding difference between a scanner and a
        // container, not a hole a viewer can land in — and a filler for it would
        // show up in a chapter menu as a real entry.
        val result: ChapterGapFill = fillChapterGaps(listOf(chapter(0.1)), 100.0, ::filler)

        assertFalse(result.changed)
        assertEquals(1, result.chapters.size)
    }

    @Test
    fun fillingTwiceDoesNotStackFillers() {
        // The caller re-runs this when the duration arrives, which is after the
        // first fill. Stacking would add a filler per time update.
        val once: ChapterGapFill = fillChapterGaps(listOf(chapter(30.0)), 100.0, ::filler)

        val twice: ChapterGapFill = fillChapterGaps(once.chapters, 100.0, ::filler)

        assertEquals(once.chapters, twice.chapters)
        assertEquals(1, twice.chapters.count { it.synthetic })
    }

    @Test
    fun chaptersOutOfOrderAreSorted() {
        // A scan that emitted them by track number rather than by time. Left
        // unsorted, the "first" chapter is whichever came out of the file first
        // and the filler goes in the wrong place.
        val result: ChapterGapFill = fillChapterGaps(
            listOf(chapter(60.0), chapter(30.0), chapter(90.0)),
            100.0,
            ::filler,
        )

        assertEquals(listOf(0.0, 30.0, 60.0, 90.0), result.chapters.map { it.startTime })
    }

    @Test
    fun aListOfNothingButFillersIsNotSomethingToFillAround() {
        // Nothing the source gave. Deriving from an empty set and then filling
        // would produce a single invented chapter presented as the item's own.
        val synthetic: List<Chapter> = listOf(Chapter(startTime = 0.0, title = UNTITLED, synthetic = true))

        val result: ChapterGapFill = fillChapterGaps(synthetic, 100.0, ::filler)

        assertFalse(result.changed)
    }

    @Test
    fun theResultCoversFromZero() {
        // The arithmetic acceptance: whatever went in, the first chapter starts
        // at or within epsilon of zero, so there is no stretch a viewer can
        // scrub into that belongs to nothing.
        for (start in listOf(0.0, 0.2, 1.0, 30.0, 99.0)) {
            val result: ChapterGapFill = fillChapterGaps(listOf(chapter(start)), 100.0, ::filler)

            assertTrue(
                result.chapters.first().startTime <= CHAPTER_GAP_EPSILON_SECONDS,
                "a list starting at $start was left with an uncovered opening",
            )
        }
    }
}
