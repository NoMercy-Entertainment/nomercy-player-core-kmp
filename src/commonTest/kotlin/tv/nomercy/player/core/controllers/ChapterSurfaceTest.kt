// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.chapters.ChapterController
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.media.Chapter
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

private val CHAPTERS = listOf(
    Chapter(startTime = 0.0, title = "Cold open"),
    Chapter(startTime = 60.0, title = "Titles"),
    Chapter(startTime = 120.0, title = "Act one"),
)

// A scan that started its first chapter partway in — an opening credits sequence
// nobody labelled. The list the whole gap-fill exists for.
private val LATE_FIRST = listOf(
    Chapter(startTime = 90.0, title = "Act one"),
)

private const val FILM_SECONDS = 3600.0

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

    // ── The gap-fill seam ────────────────────────────────────────────────────
    //
    // chapters(list) built a ChapterTrack straight from the list and the
    // controller that fills the run-up was reachable from nothing but its own
    // test. Everything below went unanswered on every real player: a scan whose
    // first chapter starts ninety seconds in left those ninety seconds inside no
    // chapter at all, and no chapters event ever fired.

    @Test
    fun theRunUpBeforeTheFirstChapterIsCoveredOnceADurationArrives() = runTest {
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.chapters(LATE_FIRST)

        // Nothing to fill up to yet, so the raw list stands and the viewer still
        // gets a working menu.
        assertEquals(1, subject.chapters().size)

        reportDuration(backend, FILM_SECONDS)

        assertEquals(2, subject.chapters().size)
        assertEquals(0.0, subject.chapters().first().startTime)
        assertTrue(subject.chapters().first().synthetic)
    }

    @Test
    fun theColdOpenBelongsToTheFillerRatherThanToNoChapter() = runTest {
        // The visible half of the same bug: a chrome showing the current chapter
        // title showed nothing at all over an unlabelled opening.
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.chapters(LATE_FIRST)
        reportDuration(backend, FILM_SECONDS)

        subject.time(10.0)

        assertEquals(ChapterController.DEFAULT_FILLER_TITLE, subject.chapter()?.title)
    }

    @Test
    fun aDurationReportedTwiceDoesNotStackFillerOnFiller() = runTest {
        // The reason the controller keeps the raw list and re-derives from it.
        // Duration is re-reported on a live item and corrected on a stream whose
        // length was estimated, and filling an already-filled list adds a second
        // filler in front of the first.
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.chapters(LATE_FIRST)

        reportDuration(backend, FILM_SECONDS)
        reportDuration(backend, FILM_SECONDS + 120.0)

        assertEquals(2, subject.chapters().size)
        assertEquals(1, subject.chapters().count { it.synthetic })
    }

    @Test
    fun ingestingChaptersAnnouncesThemEvenWhenTheListIsUnchanged() = runTest {
        // "Chapters became available" rather than "the list changed". An item
        // reloaded after an error resolves the same chapters it had, and a chrome
        // that cleared its menu on the error never gets them back if an identical
        // list is treated as nothing to say.
        val subject = ComposedPlayer(backend = FakeMediaBackend())
        subject.setup()
        val announced: MutableList<Int> = mutableListOf()
        subject.on(CoreEvents.Chapters) { announced += it.chapters.size }

        subject.chapters(CHAPTERS)
        subject.chapters(CHAPTERS)

        assertEquals(listOf(3, 3), announced)
    }

    @Test
    fun aDurationTickThatChangesNothingAnnouncesNothing() = runTest {
        // The other half of the emit rule. Duration arrives on every item and can
        // be re-reported for the same one; announcing an unchanged list each time
        // rebuilds every chapter menu on a tick that moved nothing.
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.chapters(CHAPTERS)
        val announced: MutableList<Int> = mutableListOf()
        subject.on(CoreEvents.Chapters) { announced += it.chapters.size }

        // CHAPTERS already starts at zero, so there is no run-up to cover.
        reportDuration(backend, FILM_SECONDS)

        assertEquals(emptyList(), announced)
    }

    @Test
    fun aDurationThatFillsAGapAnnouncesTheFilledList() = runTest {
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.chapters(LATE_FIRST)
        val announced: MutableList<Int> = mutableListOf()
        subject.on(CoreEvents.Chapters) { announced += it.chapters.size }

        reportDuration(backend, FILM_SECONDS)

        assertEquals(listOf(2), announced)
    }

    // The contract's writer half. A chrome that read chapter() to draw its menu
    // writes the viewer's pick back through the same name, and until this
    // existed that call did not compile.
    @Test
    fun choosingAChapterByIndexSeeksToIt() = runTest {
        val subject = player()
        subject.time(10.0)

        subject.chapter(2)

        assertEquals(120.0, subject.time())
        assertEquals("Act one", subject.chapter()?.title)
    }

    @Test
    fun choosingAChapterThatIsNotThereLeavesThePlayheadAlone() = runTest {
        val subject = player()
        subject.time(10.0)

        subject.chapter(9)

        assertEquals(10.0, subject.time())
    }

    // What a real engine does once it has read the container, which is the only
    // way a duration reaches the player.
    private fun reportDuration(backend: FakeMediaBackend, seconds: Double) {
        backend.durationValue = seconds
        backend.fire(CanonicalBackendEvent.LOADED_METADATA)
    }
}
