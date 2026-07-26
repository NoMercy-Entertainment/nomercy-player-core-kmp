// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

// One cue, in seconds.
//
// Seconds because everything else on this player is: a parser that handed back
// milliseconds would be a unit boundary in the middle of the timeline, and the
// conversion would live at every call site instead of here.
public data class VttCue(
    val start: Double,
    val end: Double,
    val body: String,
)

// A thumbnail, and where to find it inside the sheet.
//
// Sprite sheets are how a scrubber preview stays cheap: one image for a whole
// film, and each cue names a rectangle in it. Fetching a file per thumbnail
// would be thousands of requests for one drag of a progress bar.
public data class SpriteCue(
    val start: Double,
    val end: Double,
    val url: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

// HH:MM:SS.mmm or MM:SS.mmm, in seconds. Null on anything else.
//
// Both shapes because both are written. The hour field is optional in the
// format and producers leave it off for short items, so a parser that demanded
// it would reject half the files it is given.
// Four exits, one per way the text can fail to be a timestamp. Collapsing them
// into one condition would say only that the parse failed, and the point of
// each guard is that it names which part was wrong.
@Suppress("ReturnCount")
public fun parseVttTimestamp(raw: String): Double? {
    val parts: List<String> = raw.trim().split(':')
    if (parts.size !in 2..MAX_TIME_PARTS) return null

    // A comma decimal because SRT writes one, and tools converting between the
    // two formats leave it behind more often than they should.
    val seconds: Double = parts.last().replace(',', '.').toDoubleOrNull() ?: return null

    // Whole fields, hours first. An absent hour is zero rather than a failure —
    // the field is optional in the format — but an unreadable one is a failure,
    // because a file that writes something there means it.
    val whole: List<Long> = parts.dropLast(1).map { it.toLongOrNull() ?: return null }
    val minutes: Long = whole.last()
    val hours: Long = if (whole.size == MAX_TIME_PARTS - 1) whole.first() else 0L

    return hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds
}

// Every cue in a WebVTT file.
//
// One parser, replacing three hand-rolled ones that each read a slightly
// different subset. Chapters, thumbnails and subtitles are the same format, and
// three readers of one format is three chances to disagree about it.
public fun parseVtt(text: String): List<VttCue> =
    // Blocks separated by blank lines, which is the file's own structure. A
    // line-by-line state machine has to guess whether a line is a cue id, a
    // timing or body text; the blank line says so.
    text.split(BLOCK_SEPARATOR).mapNotNull(::parseBlock)

// One block, or null when it is not a cue.
//
// The header, a NOTE, a comment, a block truncated mid-download: all of them
// arrive here and none is a cue. Returning null for each rather than naming
// them keeps this to one rule — a cue is a block with a timing line.
private fun parseBlock(block: String): VttCue? {
    val lines: List<String> = block.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // A cue may carry an identifier line before its timing, and a chapter file
    // usually does. Finding the arrow rather than assuming a position handles
    // both, and skips the header and any NOTE without needing to name them.
    val timingAt: Int = lines.indexOfFirst { it.contains(ARROW) }
    if (timingAt < 0) return null

    val range: ClosedRange<Double> = parseTiming(lines[timingAt]) ?: return null

    val body: String = lines.drop(timingAt + 1).joinToString("\n")
    // A timing with nothing under it renders as a blank subtitle: a black box
    // with no text, which looks like a bug rather than silence.
    return if (body.isEmpty()) null else VttCue(range.start, range.endInclusive, body)
}

private fun parseTiming(line: String): ClosedRange<Double>? {
    val halves: List<String> = line.split(ARROW)
    if (halves.size != 2) return null

    val start: Double? = parseVttTimestamp(halves[0])
    // Cue settings ride on the end timestamp, space-separated: align, line,
    // position. Taking the whole field fails to parse every styled cue.
    val end: Double? = parseVttTimestamp(halves[1].trim().substringBefore(' '))

    return if (start == null || end == null) null else start..end
}

// The thumbnail cues, with their rectangles resolved.
//
// Cues that are not sprites are skipped rather than returned empty. A subtitle
// file handed to this by mistake should produce no thumbnails, not a scrubber
// full of zero-sized images.
public fun parseVttSprite(text: String, baseUrl: String? = null): List<SpriteCue> =
    parseVtt(text).mapNotNull { spriteFrom(it, baseUrl) }

private fun spriteFrom(cue: VttCue, baseUrl: String?): SpriteCue? {
    val fragmentAt: Int = cue.body.indexOf(FRAGMENT)
    if (fragmentAt < 0) return null

    val numbers: List<Int> = cue.body.substring(fragmentAt + FRAGMENT.length)
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
    // Three numbers is not a rectangle, and defaulting the fourth puts a
    // thumbnail somewhere it is not.
    if (numbers.size != RECT_FIELDS) return null

    return SpriteCue(
        start = cue.start,
        end = cue.end,
        url = resolveAgainst(cue.body.take(fragmentAt), baseUrl),
        x = numbers[0],
        y = numbers[1],
        width = numbers[2],
        height = numbers[3],
    )
}

// The sheet is usually named relative to the vtt that describes it, because the
// two are generated together and sit in the same directory.
private fun resolveAgainst(path: String, baseUrl: String?): String = when {
    baseUrl.isNullOrEmpty() -> path
    ABSOLUTE.containsMatchIn(path) -> path
    else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}

private val ABSOLUTE = Regex("""^[a-zA-Z][a-zA-Z0-9+\-.]*:""")

private const val ARROW = "-->"
private const val FRAGMENT = "#xywh="
private const val BLOCK_SEPARATOR = "\n\n"
private const val RECT_FIELDS = 4
private const val MAX_TIME_PARTS = 3
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
