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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterTrackTest {

    private val coldOpen = Chapter(90.0, "Cold open")
    private val titles = Chapter(150.0, "Titles")
    private val act = Chapter(210.0, "Act one")

    private val track = ChapterTrack(listOf(titles, act, coldOpen))

    @Test
    fun chaptersSortRegardlessOfTheOrderTheyArrivedIn() {
        assertEquals(listOf(coldOpen, titles, act), track.chapters)
    }

    @Test
    fun aChapterRepeatedAtTheSameStartIsOneChapter() {
        val duplicated = ChapterTrack(listOf(coldOpen, coldOpen.copy(title = "Cold open (again)")))

        // A server emitting one twice should not produce a scrubber with two
        // markers in the same place.
        assertEquals(1, duplicated.size())
    }

    @Test
    fun timeBeforeTheFirstChapterBelongsToNoChapter() {
        // Ninety seconds of cold open with no chapter over it. Claiming the
        // first one would put a title on screen before it starts.
        assertNull(track.at(0.0))
        assertNull(track.at(89.9))
        assertEquals(-1, track.indexAt(10.0))
    }

    @Test
    fun aTimeInsideAChapterFindsThatChapter() {
        assertEquals(coldOpen, track.at(90.0))
        assertEquals(coldOpen, track.at(149.9))
        assertEquals(titles, track.at(150.0))
        assertEquals(act, track.at(9_999.0))
    }

    @Test
    fun theNextStartIsWhereASkipButtonGoes() {
        assertEquals(150.0, track.nextStart(90.0))
        assertEquals(210.0, track.nextStart(150.0))
    }

    @Test
    fun thereIsNoNextStartInTheLastChapter() {
        // Null is the caller's cue to skip to the end of the item, which is a
        // length only the player knows.
        assertNull(track.nextStart(300.0))
    }

    @Test
    fun previousRestartsTheCurrentChapterOnceYouAreIntoIt() {
        // Pressing previous four seconds in almost always means "start this
        // again", which is what every music player does.
        assertEquals(150.0, track.previousStart(155.0))
    }

    @Test
    fun previousGoesBackAChapterIfYouJustGotHere() {
        assertEquals(90.0, track.previousStart(151.0))
    }

    @Test
    fun previousFromTheFirstChapterHasNowhereToGo() {
        assertNull(track.previousStart(91.0))
    }

    @Test
    fun previousBeforeAnyChapterHasNowhereToGoEither() {
        assertNull(track.previousStart(10.0))
    }

    @Test
    fun anItemWithNoChaptersAnswersEverythingWithNothing() {
        val none = ChapterTrack(emptyList())

        assertTrue(none.isEmpty())
        assertNull(none.at(42.0))
        assertNull(none.nextStart(42.0))
        assertNull(none.previousStart(42.0))
    }
}
