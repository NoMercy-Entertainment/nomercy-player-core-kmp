// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

import tv.nomercy.player.core.media.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The chapter reader, against a file taken out of the library.
//
// REAL is not a fixture written to be convenient. It is the whole of
// `11eyes.S00E01/chapters.vtt` as it sits on disk today, malformed cue
// separation and all — and that malformation is the point. Every chapters file
// in the library looks like this, so a parser tested against tidy WebVTT would
// pass and then show an empty chapter menu for the entire collection.
class ChapterCuesTest {

    @Test
    fun aRealLibraryFileYieldsItsSevenChapters() {
        val chapters: List<Chapter> = ChapterCues.parse(REAL)

        assertEquals(7, chapters.size, "read ${chapters.size} chapters from a file with seven")
    }

    @Test
    fun theTitlesAreTheOnesInTheFile() {
        // The titles are what a viewer reads in the menu, and they are the part
        // a parser confused by the missing separators gets wrong first — it
        // swallows the next cue's identifier as this cue's body.
        val titles: List<String> = ChapterCues.parse(REAL).map { it.title }

        assertEquals(
            listOf("Prologue", "Opening", "Part A", "Eyecatch", "Part B", "Ending", "Epilogue"),
            titles,
        )
    }

    @Test
    fun theTimesAreSecondsNotMilliseconds() {
        // The kit contract is seconds. Read as milliseconds, "00:00:33" is
        // thirty-three seconds either way — but the plan for this described an
        // integer-millisecond JSON field that does not exist, and a converter
        // built for it would have divided these by a thousand and put every
        // chapter in the first second of the episode.
        val chapters: List<Chapter> = ChapterCues.parse(REAL)

        assertEquals(0.0, chapters[0].startTime)
        assertEquals(33.0, chapters[1].startTime)
        assertEquals(123.0, chapters[2].startTime)
    }

    @Test
    fun chaptersComeBackInOrder() {
        val starts: List<Double> = ChapterCues.parse(REAL).map { it.startTime }

        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun aProperlySeparatedFileReadsTheSame() {
        // The writer emits blank lines between cues today, so new files are
        // well formed. Both shapes have to give the same answer or the library
        // splits into files that work and files that do not.
        val separated: String = REAL.replace("\nChapter ", "\n\nChapter ")

        assertEquals(ChapterCues.parse(REAL), ChapterCues.parse(separated))
    }

    @Test
    fun millisecondTimestampsAreReadToo() {
        // The current writer formats HH:MM:SS.mmm. The library's files are
        // HH:MM:SS. Both are WebVTT and both have to work.
        val chapters: List<Chapter> = ChapterCues.parse(
            """
            WEBVTT

            Chapter 1
            00:00:00.000 --> 00:00:33.500
            Prologue
            """.trimIndent(),
        )

        assertEquals(1, chapters.size)
        assertEquals(0.0, chapters[0].startTime)
    }

    @Test
    fun aCueWithNoTitleIsStillAChapter() {
        // A viewer can still skip to it. Dropping it because the source left
        // the name out loses a real division of the episode.
        val chapters: List<Chapter> = ChapterCues.parse(
            """
            WEBVTT

            00:00:00 --> 00:00:33
            00:00:33 --> 00:01:00
            """.trimIndent(),
        )

        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.title.isNotBlank() })
    }

    @Test
    fun aFileWithNoCuesIsEmptyRatherThanAnError() {
        // An episode with no chapters is ordinary. Throwing here would take the
        // player down over something absent by design.
        assertTrue(ChapterCues.parse("WEBVTT\n\n").isEmpty())
        assertTrue(ChapterCues.parse("").isEmpty())
        assertTrue(ChapterCues.parse("not a vtt file at all").isEmpty())
    }
}

// Copied verbatim from /Libraries/Anime/11eyes.(2009)/11eyes.S00E01/chapters.vtt.
// One blank line in the whole file, after the header — the cues run together.
private val REAL = """
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
