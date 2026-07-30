// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A real file, of the shape a lyrics provider serves: metadata tags at the top,
// then timed lines.
private val LYRICS = """
    [ar:Some Artist]
    [ti:Some Song]
    [00:12.00]First line
    [00:15.50]Second line
    [00:20.25]Third line
""".trimIndent()

private const val TOLERANCE = 1e-6

class LrcParserTest {

    @Test
    fun aLineCarriesItsTimeAndItsWords() {
        val cues: List<LrcCue> = parseLrc(LYRICS)

        assertEquals(3, cues.size)
        assertEquals(12.0, cues[0].start)
        assertEquals("First line", cues[0].text)
    }

    @Test
    fun fractionalDigitsAreAFractionOfASecondHoweverManyAreWritten() {
        // `.5` is half a second and `.05` is a twentieth. Reading both as the
        // same number is how a lyric file drifts further out with every line.
        val cues: List<LrcCue> = parseLrc("[00:01.5]half\n[00:02.05]twentieth\n[00:03.125]exact")

        assertEquals(1.5, cues[0].start, TOLERANCE)
        assertEquals(2.05, cues[1].start, TOLERANCE)
        assertEquals(3.125, cues[2].start, TOLERANCE)
    }

    @Test
    fun minutesAreSixtySecondsEach() {
        assertEquals(90.5, parseLrc("[01:30.50]late").single().start)
    }

    @Test
    fun aLineEndsWhereTheNextBegins() {
        val cues: List<LrcCue> = parseLrc(LYRICS)

        assertEquals(15.5, cues[0].end)
        assertEquals(20.25, cues[1].end)
    }

    @Test
    fun theLastLineGetsAWindowRatherThanNoDuration() {
        // The format has no end field. A zero-length last line would never be
        // the active one, so the final lyric of every track would not show.
        val cues: List<LrcCue> = parseLrc(LYRICS)

        assertEquals(25.25, cues.last().end)
    }

    @Test
    fun metadataTagsAreNotLyrics() {
        assertTrue(
            parseLrc(LYRICS).none { it.text.contains("Artist") || it.text.contains("Song") },
            "a metadata tag was read as a lyric line",
        )
    }

    @Test
    fun aRepeatedLineBecomesOneCuePerTimestamp() {
        // A chorus is written once with every time it is sung on the front of it.
        val cues: List<LrcCue> = parseLrc("[00:10.50][01:30.50]chorus")

        assertEquals(listOf(10.5, 90.5), cues.map { it.start })
        assertEquals(listOf("chorus", "chorus"), cues.map { it.text })
    }

    @Test
    fun linesComeBackInTimeOrderWhateverOrderTheyWereWritten() {
        // The end of every cue is the next one's start, so an unsorted file would
        // otherwise produce negative-length lines.
        val cues: List<LrcCue> = parseLrc("[00:20.00]later\n[00:10.00]earlier")

        assertEquals(listOf(10.0, 20.0), cues.map { it.start })
        assertEquals(20.0, cues[0].end)
    }

    @Test
    fun enhancedLrcKeepsWhenEachWordIsSung() {
        val cue: LrcCue = parseLrc("[00:01.00]<00:01.00>Never <00:01.50>gonna <00:02.00>give").single()

        assertEquals(listOf("Never", "gonna", "give"), cue.words.map { it.text })
        assertEquals(listOf(1.0, 1.5, 2.0), cue.words.map { it.start })
    }

    @Test
    fun anEnhancedLineStillReadsAsAWholeLine() {
        // A consumer drawing lines must not have to reassemble one from words.
        val cue: LrcCue = parseLrc("[00:01.00]<00:01.00>Never <00:01.50>gonna").single()

        assertEquals("Never gonna", cue.text)
    }

    @Test
    fun textBeforeTheFirstWordTagBelongsToTheLine() {
        val cue: LrcCue = parseLrc("[00:01.00]Oh <00:02.00>baby").single()

        assertEquals("Oh baby", cue.text)
        assertEquals(listOf("baby"), cue.words.map { it.text })
    }

    @Test
    fun aPlainLineHasNoWords() {
        assertTrue(parseLrc("[00:01.00]plain").single().words.isEmpty())
    }

    @Test
    fun aBracketedTimeInTheMiddleOfALyricIsText() {
        // Scanning the whole line for timestamps would turn one line into two,
        // the second starting at a time nothing was sung.
        val cues: List<LrcCue> = parseLrc("[00:01.00]wait [00:02.00] for it")

        assertEquals(1, cues.size)
        assertEquals("wait [00:02.00] for it", cues.single().text)
    }

    @Test
    fun aLineWithNoTimestampIsNotACue() {
        assertTrue(parseLrc("just some text\n\n[00:01.00]timed").size == 1)
    }

    @Test
    fun nothingInIsNothingOut() {
        assertTrue(parseLrc("").isEmpty())
        assertTrue(parseLrc("\n\n").isEmpty())
    }

    @Test
    fun aWindowsFileReadsTheSameAsAUnixOne() {
        assertEquals(
            parseLrc("[00:01.00]one\n[00:02.00]two"),
            parseLrc("[00:01.00]one\r\n[00:02.00]two"),
        )
    }
}
