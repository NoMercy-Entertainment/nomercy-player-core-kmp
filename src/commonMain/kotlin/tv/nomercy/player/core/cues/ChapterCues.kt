// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

import tv.nomercy.player.core.media.Chapter

// Chapters, read out of the WebVTT file the server writes beside the media.
//
// Not out of a JSON field. The plan for this described a playlist entry with
// integer-millisecond start and end times; there is no such thing. The server
// writes `chapters.vtt` next to the rendition — WEBVTT, one cue per chapter,
// the title as the cue body — and that file is what a client gets.
//
// Deliberately lenient about cue separation, and that is the whole reason this
// is not just `parseVtt`. WebVTT separates cues with blank lines. The files
// already in the library have exactly one blank line in them, after the WEBVTT
// header, and then seven chapters run together:
//
//     WEBVTT
//
//     Chapter 1
//     00:00:00 --> 00:00:33
//     Prologue
//     Chapter 2
//     00:00:33 --> 00:02:03
//     Opening
//
// A spec-compliant parser reads that as one cue whose body is the rest of the
// file. The writer emits separators today, so these are older files — but they
// are what every title in the library actually has, and a chapter menu that
// shows nothing for all of them is worse than one that reads what is there.
//
// So cues are found by their timestamp lines rather than by blank lines. A
// timestamp line is unambiguous; a missing blank line is not.
public object ChapterCues {

    // Anything with an arrow between two timestamps, whether or not the
    // timestamps carry milliseconds — the library's files use HH:MM:SS and the
    // writer now emits HH:MM:SS.mmm.
    private val TIMING = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")

    public fun parse(text: String): List<Chapter> {
        val lines: List<String> = text.lineSequence().map { it.trim() }.toList()

        return lines.indices
            .mapNotNull { index -> chapterAt(lines, index) }
            .sortedBy { it.startTime }
    }

    // Null for a line that is not a cue's timings, or whose start cannot be
    // read. Both are ordinary — most lines in the file are identifiers and
    // titles — so this is a filter rather than an error.
    private fun chapterAt(lines: List<String>, index: Int): Chapter? {
        val timing = TIMING.find(lines[index]) ?: return null
        val start: Double = parseVttTimestamp(timing.groupValues[1]) ?: return null

        return Chapter(startTime = start, title = titleAfter(lines, index))
    }

    // The first non-empty line after the timings that is not itself another
    // cue's identifier or timing.
    //
    // The identifier line ("Chapter 3") sits before the timings and the title
    // after, so reading forward is right — but with no blank separators the
    // next cue's identifier is only one line further on, which is why this
    // stops at the first line it takes rather than gathering until blank.
    private fun titleAfter(lines: List<String>, timingIndex: Int): String {
        val next: String = lines.getOrNull(timingIndex + 1)?.trim().orEmpty()

        return next.takeIf { it.isNotEmpty() && TIMING.find(it) == null } ?: UNTITLED
    }
}

// A chapter with no title is still a chapter — the viewer can still skip to it.
// Dropping it because the source left the name out would lose a real division.
private const val UNTITLED = "Chapter"
