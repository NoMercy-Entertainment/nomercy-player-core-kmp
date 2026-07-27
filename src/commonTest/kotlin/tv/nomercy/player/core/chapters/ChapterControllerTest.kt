// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.chapters

import tv.nomercy.player.core.cues.ChapterCues
import tv.nomercy.player.core.media.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The seam every chapter path funnels through.
//
// What it has to get right is the re-run: duration is not known when chapters
// arrive, and filling again when it appears must not stack a filler on top of
// the last one.
class ChapterControllerTest {

    private fun controller() = ChapterController { "Untitled" }

    @Test
    fun chaptersFromTheRealFileSurviveIngestion() {
        // The same bytes ChapterCuesTest reads, taken through the controller —
        // so the two halves are known to fit rather than assumed to.
        val controller: ChapterController = controller()

        controller.ingest(ChapterCues.parse(REAL_VTT))

        assertEquals(7, controller.chapters().size)
        assertEquals("Prologue", controller.chapters().first().title)
    }

    @Test
    fun aRunUpBeforeTheFirstChapterIsCovered() {
        // A source that starts its first chapter a minute in leaves the opening
        // minute belonging to nothing, and a scrubber lands the viewer in a
        // chapter that does not exist.
        val controller: ChapterController = controller()

        // The duration first, and that ordering is the rule rather than a
        // convenience. fillChapterGaps refuses to fill without one — a live
        // stream or metadata that has not arrived has no end to fill up to —
        // so a controller that filled on ingest alone would be inventing a
        // chapter list for an item whose length nobody knows yet.
        controller.durationChanged(1_400.0)
        controller.ingest(listOf(Chapter(startTime = 60.0, title = "Part A")))

        assertEquals(0.0, controller.chapters().first().startTime)
        assertTrue(controller.chapters().first().synthetic, "the filler is not marked as one")
    }

    @Test
    fun aDurationThatArrivesLaterIsNotAChangeIfNothingMoves() {
        // The engine reports duration on every metadata refresh. Re-announcing
        // an identical list makes a chrome rebuild its menu for nothing.
        //
        // A drifting duration counts as the same case, and it is the one worth
        // asserting: a container whose length is re-estimated by a fraction of a
        // second must not announce a chapter list that is element-for-element
        // what the chrome already has.
        val controller: ChapterController = controller()
        controller.ingest(listOf(Chapter(startTime = 60.0, title = "Part A")))
        controller.durationChanged(1_400.0)

        assertFalse(controller.durationChanged(1_400.0), "the same duration announced a change")
        assertFalse(controller.durationChanged(1_400.03), "a re-estimate announced a change")
    }

    @Test
    fun refillingDoesNotStackFillersOnFillers() {
        // The bug this design exists to prevent. Filling a list that was
        // already filled adds a second cover for the same run-up, and the menu
        // grows an extra entry every time the engine refreshes its duration.
        val controller: ChapterController = controller()
        controller.durationChanged(1_400.0)
        controller.ingest(listOf(Chapter(startTime = 60.0, title = "Part A")))
        val afterFirst: Int = controller.chapters().size

        controller.durationChanged(1_400.0)
        controller.durationChanged(2_800.0)
        controller.durationChanged(3_000.0)

        assertEquals(afterFirst, controller.chapters().size, "fillers stacked: ${controller.chapters()}")
    }

    @Test
    fun ingestingAgainReplacesRatherThanAppends() {
        // A queue advancing to the next item ingests that item's chapters. The
        // previous title's must not still be in the list.
        val controller: ChapterController = controller()
        controller.ingest(listOf(Chapter(startTime = 0.0, title = "First item")))

        controller.ingest(listOf(Chapter(startTime = 0.0, title = "Second item")))

        assertEquals(1, controller.chapters().size)
        assertEquals("Second item", controller.chapters().single().title)
    }

    @Test
    fun chaptersComeBackInOrderWhateverOrderTheyArrivedIn() {
        val controller: ChapterController = controller()

        controller.ingest(
            listOf(
                Chapter(startTime = 120.0, title = "Third"),
                Chapter(startTime = 0.0, title = "First"),
                Chapter(startTime = 60.0, title = "Second"),
            ),
        )

        assertEquals(listOf("First", "Second", "Third"), controller.chapters().map { it.title })
    }

    @Test
    fun clearingLeavesNothingBehind() {
        // A player released between items must not answer with the last one's
        // chapters while the next is loading.
        val controller: ChapterController = controller()
        controller.ingest(listOf(Chapter(startTime = 0.0, title = "Part A")))

        controller.clear()

        assertTrue(controller.chapters().isEmpty())
    }

    @Test
    fun anImpossibleDurationDoesNotStripACoveredList() {
        // Engines report zero before a container is read and libVLC reports -1
        // for "not yet", and both arrive after a real duration on a seek or a
        // source reload.
        //
        // The covered list first, because that is what there is to lose. refill
        // derives from the source every time, so a duration that reads as
        // unknown makes the fill decline and hands back the raw list — the
        // run-up filler vanishing out of a menu the viewer has open. Asserting
        // this on a list that was never filled asserts nothing, which is how
        // this test first passed with the guard deleted.
        val controller: ChapterController = controller()
        controller.durationChanged(1_400.0)
        controller.ingest(listOf(Chapter(startTime = 60.0, title = "Part A")))
        val covered: List<Chapter> = controller.chapters()
        assertTrue(covered.first().synthetic, "nothing was covered, so nothing is at risk")

        assertFalse(controller.durationChanged(0.0))
        assertFalse(controller.durationChanged(-1.0))
        assertEquals(covered, controller.chapters(), "an impossible duration stripped the filler")
    }
}

// The first three cues of the library file ChapterCuesTest reads in full.
private val REAL_VTT = """
WEBVTT

Chapter 1
00:00:00 --> 00:00:33
Prologue
Chapter 2
00:00:33 --> 00:02:03
Opening
Chapter 3
00:02:03 --> 00:12:11
Part A
Chapter 4
00:12:11 --> 00:12:18
Eyecatch
Chapter 5
00:12:18 --> 00:21:51
Part B
Chapter 6
00:21:51 --> 00:23:21
Ending
Chapter 7
00:23:21 --> 00:23:55
Epilogue
""".trimIndent()
