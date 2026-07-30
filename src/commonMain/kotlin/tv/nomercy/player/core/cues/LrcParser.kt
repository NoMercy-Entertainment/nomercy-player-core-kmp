// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

// One word of a line, and when it is sung.
//
// Enhanced LRC is how a karaoke display knows which syllable to light, and the
// timing is in the file whether or not a consumer draws it. Dropping it in the
// parser would mean the information never reaches anybody who could.
public data class LrcWord(
    val start: Double,
    val text: String,
)

// One line of lyrics.
//
// [words] is empty for plain LRC, which is most of it. A file that carries
// per-word timing fills it, and [text] is still the whole line — a consumer
// that only draws lines does not have to know which kind of file arrived.
public data class LrcCue(
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<LrcWord> = emptyList(),
)

// Every line of an LRC or enhanced LRC file, in order.
//
//   plain            [mm:ss.xx]line text
//   enhanced         [mm:ss.xx]<mm:ss.xx>word <mm:ss.xx>word
//   repeated line    [00:10.50][01:30.50]same text — one cue per timestamp
//   metadata         [ar:Artist], [ti:Title] — not lyrics, not cues
//
// End times come from the next line's start, because the format has no end
// field. The last line gets five seconds, which is long enough to read and short
// enough that it does not sit on the screen after the track has moved on.
public fun parseLrc(text: String): List<LrcCue> {
    if (text.isEmpty()) return emptyList()

    // Sorted, because a repeated-line file writes its later timestamp on an
    // earlier line and the end time of every cue is the next one's start.
    val ordered: List<Pair<Double, LrcBody>> = text.lines()
        .flatMap { line -> timedBodies(line.trim()) }
        .sortedBy { it.first }

    return ordered.mapIndexed { index, entry ->
        val start: Double = entry.first
        LrcCue(
            start = start,
            end = ordered.getOrNull(index + 1)?.first ?: (start + TRAILING_SECONDS),
            text = entry.second.text,
            words = entry.second.words,
        )
    }
}

// What one line contributes, which is nothing at all more often than it looks.
//
// A blank line, a metadata tag and a line with no leading timestamp are all "not
// a lyric", and a metadata tag is told apart by its shape — letters after the
// bracket where a cue has digits, and the tag is the whole line. Testing the
// shape rather than listing the tags means a file carrying one this library has
// never heard of is still not read as a lyric.
private fun timedBodies(trimmed: String): List<Pair<Double, LrcBody>> {
    if (trimmed.isEmpty() || METADATA_TAG.matches(trimmed)) return emptyList()

    val heading: LrcHeading = leadingTimestamps(trimmed)
    if (heading.starts.isEmpty()) return emptyList()

    val body: LrcBody = splitWords(trimmed.substring(heading.length))
    return heading.starts.map { start -> start to body }
}

// The timestamps at the head of a line, and how far into it they reach.
private class LrcHeading(val starts: List<Double>, val length: Int)

// A line's text, and its words when it has timed ones.
private class LrcBody(val text: String, val words: List<LrcWord>)

// Only the timestamps a line STARTS with, and only while they are contiguous.
//
// A bracketed time in the middle of a lyric is text, not a cue: stopping at the
// first gap is what tells the two apart, and scanning the whole line would turn
// one line into several starting at times nothing was sung.
private fun leadingTimestamps(line: String): LrcHeading {
    val starts: MutableList<Double> = mutableListOf()
    var cursor = 0
    var match: MatchResult? = LINE_TIMESTAMP.find(line)

    while (match != null && match.range.first == cursor) {
        starts += toSeconds(match.groupValues[MINUTES], match.groupValues[SECONDS], match.groupValues[FRACTION])
        cursor = match.range.last + 1
        match = match.next()
    }

    return LrcHeading(starts = starts, length = cursor)
}

// A line's words, when it has timed ones.
//
// The tag comes BEFORE the word it times, so the text between one tag and the
// next belongs to the tag before it — and text before the first tag belongs to
// no word at all, which is a line that starts singing partway through.
private fun splitWords(body: String): LrcBody {
    if (!body.contains('<')) return LrcBody(text = body, words = emptyList())

    val words: MutableList<LrcWord> = mutableListOf()
    val untimed = StringBuilder()
    var cursor = 0

    for (match in WORD_TIMESTAMP.findAll(body)) {
        appendBetween(body.substring(cursor, match.range.first), words, untimed)
        words += LrcWord(
            start = toSeconds(match.groupValues[MINUTES], match.groupValues[SECONDS], match.groupValues[FRACTION]),
            text = "",
        )
        cursor = match.range.last + 1
    }

    appendBetween(body.substring(cursor), words, untimed)

    if (words.isEmpty()) return LrcBody(text = untimed.toString().trim(), words = emptyList())

    // The whole line as well as its words, so a consumer drawing lines never has
    // to reassemble one from the pieces.
    val whole: String = (listOf(untimed.toString().trim()) + words.map { it.text })
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    return LrcBody(text = whole.trim(), words = words.toList())
}

private fun appendBetween(between: String, words: MutableList<LrcWord>, untimed: StringBuilder) {
    if (words.isNotEmpty() && between.isNotEmpty()) {
        words[words.lastIndex] = words.last().copy(text = between.trim())
    } else {
        untimed.append(between)
    }
}

private fun toSeconds(minutes: String, seconds: String, fraction: String): Double =
    (minutes.toLongOrNull() ?: 0L) * SECONDS_PER_MINUTE + (seconds.toLongOrNull() ?: 0L) + fractionSeconds(fraction)

// However many digits are written, they are a fraction of a second. `.5` is half
// a second and `.05` is a twentieth: reading both as the same number is the
// classic way to get a lyric file that drifts further out with every line.
private fun fractionSeconds(raw: String): Double {
    if (raw.isEmpty()) return 0.0

    val millis: String = when (raw.length) {
        1 -> raw + "00"
        2 -> raw + "0"
        MILLIS_DIGITS -> raw
        else -> raw.substring(0, MILLIS_DIGITS)
    }

    return (millis.toLongOrNull() ?: 0L) / MILLIS_PER_SECOND
}

private val LINE_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
private val WORD_TIMESTAMP = Regex("""<(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?>""")
private val METADATA_TAG = Regex("""\[[a-z]+:[^]]*]""", RegexOption.IGNORE_CASE)

private const val MINUTES = 1
private const val SECONDS = 2
private const val FRACTION = 3

private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_DIGITS = 3
private const val MILLIS_PER_SECOND = 1_000.0
private const val TRAILING_SECONDS = 5.0
