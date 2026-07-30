// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

import tv.nomercy.player.core.events.CueEvent
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.CueParserRegistry

// The three formats everybody has, wired to the registry that resolves them.
//
// A registry with nothing in it resolves nothing, and that is what shipped: the
// parsers existed as functions and no player knew about them, so
// resolveCueParser returned null for every .vtt and .lrc on every platform and
// the lyrics plugin — whose whole job is synced lyrics — could not read a file it
// was handed. The web registers these at setup; this seeds them at construction,
// which is the same answer one step earlier.

// `.lrc`, and the content types a server serves one with.
public object LrcCueParser : CueParser {
    override val id: String = "lrc"

    override fun canParse(url: String, contentType: String?): Boolean =
        LRC_EXTENSION.containsMatchIn(url) || (contentType != null && LRC_MIME.matches(contentType))

    // Per-word timing is dropped here and only here: [CueEvent] carries a line
    // and a span, and enhanced LRC carries syllables. A consumer that wants them
    // calls [parseLrc], which keeps them.
    override fun parse(raw: String, baseUrl: String?): List<CueEvent> =
        parseLrc(raw).map { CueEvent(startTime = it.start, endTime = it.end, text = it.text) }
}

// Plain subtitle WebVTT. Declines a sprite sheet rather than returning cues
// whose text is a rectangle.
public object VttSubtitleCueParser : CueParser {
    override val id: String = "vtt"

    override fun canParse(url: String, contentType: String?): Boolean = when {
        SPRITE_HINT.containsMatchIn(url) -> false
        VTT_EXTENSION.containsMatchIn(url) -> true
        else -> contentType != null && VTT_MIME.matches(contentType)
    }

    override fun parse(raw: String, baseUrl: String?): List<CueEvent> =
        parseVttSubtitles(raw).map { CueEvent(startTime = it.start, endTime = it.end, text = it.body) }
}

// Sprite WebVTT — `*.sprite.vtt`, `*.sprites.vtt`.
//
// Sprite VTT shares the extension with subtitles, so the url is the only thing
// that tells them apart before the file arrives. It ranks behind plain VTT and
// activates on the hint alone.
public object SpriteVttCueParser : CueParser {
    override val id: String = "sprite-vtt"

    override fun canParse(url: String, contentType: String?): Boolean = SPRITE_HINT.containsMatchIn(url)

    // The cue body, with the sheet's url resolved against [baseUrl]. A rectangle
    // does not fit in [CueEvent] and this does not pretend otherwise —
    // [parseVttSprite] returns [SpriteCue] with the numbers in fields, and a
    // scrubber preview should call it. This exists so resolution answers for a
    // sprite url the way the web's registry does, rather than reporting that
    // nothing can read the file.
    override fun parse(raw: String, baseUrl: String?): List<CueEvent> =
        parseVttSprite(raw, baseUrl).map {
            CueEvent(startTime = it.start, endTime = it.end, text = spriteReference(it))
        }
}

// What the kit registers by itself, in the web's order.
public val builtInCueParsers: List<CueParser> = listOf(
    LrcCueParser,
    VttSubtitleCueParser,
    SpriteVttCueParser,
)

// Seed the built-ins, and return the registry so a construction reads as one
// expression.
//
// Reversed, because each one goes in at the bottom: pushing them in order would
// leave sprite-vtt below lrc, and the point of seeding low is that anything a
// consumer registers — before setup or after — outranks all three without them
// needing to know these were here.
public fun CueParserRegistry.registerBuiltIns(): CueParserRegistry {
    builtInCueParsers.asReversed().forEach { parser -> register(parser, atLowestPriority = true) }
    return this
}

private fun spriteReference(cue: SpriteCue): String =
    "${cue.url}#xywh=${cue.x},${cue.y},${cue.width},${cue.height}"

private val LRC_EXTENSION = Regex("""\.lrc(\?|$)""", RegexOption.IGNORE_CASE)
private val LRC_MIME = Regex("""application/x-(lrc|lyrics)|text/lrc""", RegexOption.IGNORE_CASE)
private val VTT_EXTENSION = Regex("""\.vtt(\?|$)""", RegexOption.IGNORE_CASE)
private val VTT_MIME = Regex("""text/vtt""", RegexOption.IGNORE_CASE)
private val SPRITE_HINT = Regex("""sprites?\.vtt(\?|$)""", RegexOption.IGNORE_CASE)
