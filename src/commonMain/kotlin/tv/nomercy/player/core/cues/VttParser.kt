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
//
// [settings] defaults to empty rather than being absent: every existing
// positional and copy() call site still compiles, and a cue with no cue-
// settings line reads the same as one this parser has never seen.
public data class VttCue(
    val start: Double,
    val end: Double,
    val body: String,
    val settings: VttCueSettings = VttCueSettings(),
)

// `align`, `line`, `size` off the timing line. `position:` and `vertical:` are
// intentionally not read — out of scope for the cue-list use cases, matching
// the web parser's own documented cut.
public data class VttCueSettings(
    val alignment: String? = null,
    val linePosition: Int? = null,
    val size: Int? = null,
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

// What a subtitle consumer gets from
// [tv.nomercy.player.core.ports.CueParser], once timing has gone into
// [Cue.start]/[Cue.end]. The web's `VTTSubtitlePayload` shape: text plus the
// positioning a renderer needs to reproduce the cue without re-parsing the
// source.
public data class VttSubtitlePayload(
    override val text: String,
    val alignment: String? = null,
    val linePosition: Int? = null,
    val size: Int? = null,
) : TextPayload

// A sprite consumer's payload, mirroring the web's `VTTSpritePayload` field
// names — `w`/`h`, not `width`/`height` — so a rectangle crossing the wire
// reads the same regardless of which client wrote it.
public data class VttSpritePayload(
    val url: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
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
    normalise(text).split(BLOCK_SEPARATOR).mapNotNull(::parseBlock)

// The same cues, with the inline markup taken out.
//
// A wrapper rather than a second parser: WebVTT permits `<i>`, `<b>`, `<c.loud>`
// and `<v Speaker>` inside a cue, and a subtitle drawn from the raw body shows
// the angle brackets to the viewer. Everything else about reading the file is
// identical, and two readers of one format is two chances to disagree about it.
//
// `align`, `line`, `size` ride along on [VttCue.settings] — `copy()` below only
// touches `body`. A renderer with its own richer cue type still wants
// tv.nomercy.player.video.subtitles.SubtitleCue; this is the shape
// [tv.nomercy.player.core.ports.CueParser]'s built-in hands to a plugin.
public fun parseVttSubtitles(text: String): List<VttCue> =
    parseVtt(text).map { it.copy(body = it.body.replace(INLINE_TAG, "").trim()) }

// CRLF, which the format allows and this did not survive.
//
// The block separator is two newlines. A CRLF file separates its blocks with
// `\r\n\r\n`, so the split never matched, the whole file arrived as ONE block,
// and the first arrow in it produced a single cue whose body was the rest of the
// document. Subtitles became one cue holding the entire file; thumbnails came
// back empty, because the sprite rectangle then had far more than four numbers
// in it. Most encoders emit CRLF, so this was the common case, not the edge.
//
// A separator line carrying a space or a tab is the same failure from a second
// direction, and it survived the CRLF fix because line endings were not what was
// wrong with it. Such a line is emptied before the split so it separates like
// the blank line it was meant to be — the web parser does this, and the defect it
// closed there is on record.
//
// The BOM strip is alignment with the web parser, not a fix — a BOM rides on the
// `WEBVTT` header, and this reads the header as "a block with no arrow" rather
// than by name, so it was already skipped either way.
private fun normalise(text: String): String {
    val unified: String = text.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n')
    return BLANK_LINE.replace(unified, "")
}

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

    val timing: VttTiming = parseTiming(lines[timingAt]) ?: return null

    val body: String = lines.drop(timingAt + 1).joinToString("\n")
    // A timing with nothing under it renders as a blank subtitle: a black box
    // with no text, which looks like a bug rather than silence.
    return if (body.isEmpty()) null else VttCue(timing.range.start, timing.range.endInclusive, body, timing.settings)
}

// The timing line's two halves: the range every cue needs, and the settings
// only a styled one carries.
private class VttTiming(val range: ClosedRange<Double>, val settings: VttCueSettings)

private fun parseTiming(line: String): VttTiming? {
    val halves: List<String> = line.split(ARROW)
    if (halves.size != 2) return null

    val start: Double? = parseVttTimestamp(halves[0])
    val tail: String = halves[1].trim()
    // Cue settings ride on the end timestamp, space-separated: align, line,
    // size (position and vertical are out of scope — see [VttCueSettings]).
    // Taking the whole field for the timestamp fails to parse every styled cue.
    val end: Double? = parseVttTimestamp(tail.substringBefore(' '))

    if (start == null || end == null) return null
    return VttTiming(start..end, parseCueSettings(tail.substringAfter(' ', missingDelimiterValue = "")))
}

// `align:start line:50% size:80%`, tolerant of a missing trailing `%` and
// silent on anything it does not recognise — a malformed cue still renders
// centred at the safe-area baseline rather than failing to parse at all.
private fun parseCueSettings(raw: String): VttCueSettings {
    if (raw.isBlank()) return VttCueSettings()

    var alignment: String? = null
    var linePosition: Int? = null
    var size: Int? = null

    for (token in raw.trim().split(WHITESPACE)) {
        val sep: Int = token.indexOf(':')
        if (sep < 0) continue
        val key: String = token.take(sep).lowercase()
        val value: String = token.substring(sep + 1)

        when (key) {
            "align" -> alignment = normaliseAlignment(value)
            "line" -> linePosition = parsePercent(value) ?: linePosition
            "size" -> size = parsePercent(value) ?: size
        }
    }

    return VttCueSettings(alignment = alignment, linePosition = linePosition, size = size)
}

private fun normaliseAlignment(raw: String): String? = when (raw.lowercase()) {
    "start", "left" -> "start"
    "end", "right" -> "end"
    "center", "middle", "centre" -> "center"
    else -> null
}

// `line:auto` and negative line numbers are not meaningful here — only a
// percentage 0-100 is, matching the web parser.
private fun parsePercent(raw: String): Int? {
    val match: MatchResult = PERCENT.matchEntire(raw) ?: return null
    val value: Int = match.groupValues[1].toIntOrNull() ?: return null
    return value.takeIf { it in MIN_PERCENT..MAX_PERCENT }
}

// The thumbnail cues, with their rectangles resolved.
//
// Cues that are not sprites are skipped rather than returned empty. A subtitle
// file handed to this by mistake should produce no thumbnails, not a scrubber
// full of zero-sized images.
public fun parseVttSprite(text: String, baseUrl: String? = null): List<SpriteCue> =
    parseVtt(text).mapNotNull { spriteFrom(it, baseUrl) }

private fun spriteFrom(cue: VttCue, baseUrl: String?): SpriteCue? {
    // Anchored, and the whole body has to be the reference. Searching for the
    // fragment and splitting what followed accepted a cue with trailing text
    // after the rectangle, and accepted a negative width or height — neither of
    // which is a sprite, and both of which then drew nothing at a position
    // nothing else agreed with.
    val fields = SPRITE_FRAGMENT.matchEntire(cue.body.trim())?.groupValues ?: return null

    return SpriteCue(
        start = cue.start,
        end = cue.end,
        url = resolveAgainst(fields[1], baseUrl),
        x = fields[2].toInt(),
        y = fields[3].toInt(),
        width = fields[4].toInt(),
        height = fields[5].toInt(),
    )
}

// The sheet is named relative to the vtt that describes it, because the two are
// generated together and sit in the same directory.
//
// `baseUrl` is the URL of the VTT FILE, so the directory is everything up to its
// last slash. This appended to the whole thing, which resolved every relative
// sheet to `.../sprite.vtt/sprite.webp` — a 404, so the scrub preview could not
// have painted a frame no matter what else was right.
private fun resolveAgainst(path: String, baseUrl: String?): String = when {
    baseUrl.isNullOrEmpty() -> path
    ABSOLUTE.containsMatchIn(path) -> path
    // A root-relative sheet resolves against the ORIGIN, not the directory.
    path.startsWith('/') -> (ORIGIN.find(baseUrl)?.value ?: "") + path
    else -> directoryOf(baseUrl) + path
}

// Everything up to and including the base's last slash. A base with no slash at
// all has no directory to resolve against, so the path stands alone.
private fun directoryOf(baseUrl: String): String =
    if (baseUrl.contains('/')) baseUrl.substringBeforeLast('/') + "/" else ""

// Only http and https, matching the web. Treating any scheme as absolute meant a
// path the web would have joined to the base was left alone instead.
private val INLINE_TAG = Regex("<[^>]+>")
private val ABSOLUTE = Regex("""^https?://""")
private val ORIGIN = Regex("""^https?://[^/]+""")
private val SPRITE_FRAGMENT = Regex("""([^#]+)#xywh=(-?\d+),(-?\d+),(\d+),(\d+)""")

private const val ARROW = "-->"
private const val BOM = "﻿"

// Two or more, so three blank lines between cues do not produce an empty block
// the rest of this has to recognise as "not a cue".
private val BLOCK_SEPARATOR = Regex("\n{2,}")

private val BLANK_LINE = Regex("""^[ \t]+$""", RegexOption.MULTILINE)
private const val MAX_TIME_PARTS = 3
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

private val WHITESPACE = Regex("""\s+""")

// Trailing `%` optional — legacy files write bare numbers.
private val PERCENT = Regex("""^(-?\d+)%?$""")
private const val MIN_PERCENT = 0
private const val MAX_PERCENT = 100
