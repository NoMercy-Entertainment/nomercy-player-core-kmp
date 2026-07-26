// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.media.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val CHAPTERS = listOf(
    Chapter(startTime = 0.0, title = "Cold open"),
    Chapter(startTime = 60.0, title = "Titles"),
    Chapter(startTime = 120.0, title = "Act one"),
)

// Chapter navigation, and the previous-button behaviour every viewer already
// has in their hands.
class ChapterSurfaceTest {

    private suspend fun player(): ComposedPlayer {
        val subject = ComposedPlayer(backend = FakeMediaBackend())
        subject.setup()
        subject.queue(listOf(TestItem("a")))
        subject.chapters(CHAPTERS)
        return subject
    }

    @Test
    fun theCurrentChapterIsTheOneThePlayheadIsInside() = runTest {
        val subject = player()
        subject.time(90.0)

        assertEquals("Titles", subject.chapter()?.title)
    }

    @Test
    fun nextGoesToTheFollowingChapter() = runTest {
        val subject = player()
        subject.time(70.0)

        subject.nextChapter()

        assertEquals(120.0, subject.time())
    }

    @Test
    fun nextAtTheLastChapterDoesNotRestartTheFilm() = runTest {
        // Null at the last chapter is the caller's cue to skip to the end of the
        // item. Jumping to zero there would restart it, which is the worst
        // possible answer to "next".
        val subject = player()
        subject.time(150.0)

        subject.nextChapter()

        assertEquals(150.0, subject.time())
    }

    @Test
    fun previousMidChapterRestartsTheCurrentOne() = runTest {
        // What every music player does, because pressing previous mid-chapter
        // almost always means "start this again".
        val subject = player()
        subject.time(90.0)

        subject.previousChapter()

        assertEquals(60.0, subject.time())
    }

    @Test
    fun previousJustAfterAChapterStartGoesToTheOneBefore() = runTest {
        val subject = player()
        subject.time(61.0)

        subject.previousChapter()

        assertEquals(0.0, subject.time())
    }

    @Test
    fun anItemWithNoChaptersHasNoneRatherThanThrowing() = runTest {
        val subject = ComposedPlayer(backend = FakeMediaBackend())
        subject.setup()

        assertEquals(emptyList(), subject.chapters())
        assertNull(subject.chapter())
        subject.nextChapter()
        subject.previousChapter()
    }
}
