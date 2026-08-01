// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

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
//
// Each parser's T is the web's payload shape for that format — LrcPayload,
// VttSubtitlePayload, VttSpritePayload — not the flattened CueEvent this used
// to return. Word timing, cue-box positioning and sprite rectangles now cross
// this seam instead of being discarded on the way through it.

// `.lrc`, and the content types a server serves one with.
public object LrcCueParser : CueParser<LrcPayload> {
    override val id: String = "lrc"

    override fun canParse(url: String, contentType: String?): Boolean =
        LRC_EXTENSION.containsMatchIn(url) || (contentType != null && LRC_MIME.matches(contentType))

    override fun parse(raw: String, baseUrl: String?): List<Cue<LrcPayload>> =
        parseLrc(raw).map {
            Cue(start = it.start, end = it.end, payload = LrcPayload(text = it.text, words = it.words))
        }
}

// Plain subtitle WebVTT. Declines a sprite sheet rather than returning cues
// whose text is a rectangle.
public object VttSubtitleCueParser : CueParser<VttSubtitlePayload> {
    override val id: String = "vtt"

    override fun canParse(url: String, contentType: String?): Boolean = when {
        SPRITE_HINT.containsMatchIn(url) -> false
        VTT_EXTENSION.containsMatchIn(url) -> true
        else -> contentType != null && VTT_MIME.matches(contentType)
    }

    override fun parse(raw: String, baseUrl: String?): List<Cue<VttSubtitlePayload>> =
        parseVttSubtitles(raw).map {
            Cue(
                start = it.start,
                end = it.end,
                payload = VttSubtitlePayload(
                    text = it.body,
                    alignment = it.settings.alignment,
                    linePosition = it.settings.linePosition,
                    size = it.settings.size,
                ),
            )
        }
}

// Sprite WebVTT — `*.sprite.vtt`, `*.sprites.vtt`.
//
// Sprite VTT shares the extension with subtitles, so the url is the only thing
// that tells them apart before the file arrives. It ranks behind plain VTT and
// activates on the hint alone.
public object SpriteVttCueParser : CueParser<VttSpritePayload> {
    override val id: String = "sprite-vtt"

    override fun canParse(url: String, contentType: String?): Boolean = SPRITE_HINT.containsMatchIn(url)

    // The rectangle itself, not a string reference into it. [parseVttSprite]
    // already resolves the sheet's url against [baseUrl]; this just carries its
    // fields through rather than flattening them into text a consumer has to
    // re-parse.
    override fun parse(raw: String, baseUrl: String?): List<Cue<VttSpritePayload>> =
        parseVttSprite(raw, baseUrl).map {
            Cue(
                start = it.start,
                end = it.end,
                payload = VttSpritePayload(url = it.url, x = it.x, y = it.y, w = it.width, h = it.height),
            )
        }
}

// What the kit registers by itself, in the web's order.
public val builtInCueParsers: List<CueParser<*>> = listOf(
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

private val LRC_EXTENSION = Regex("""\.lrc(\?|$)""", RegexOption.IGNORE_CASE)
private val LRC_MIME = Regex("""application/x-(lrc|lyrics)|text/lrc""", RegexOption.IGNORE_CASE)
private val VTT_EXTENSION = Regex("""\.vtt(\?|$)""", RegexOption.IGNORE_CASE)
private val VTT_MIME = Regex("""text/vtt""", RegexOption.IGNORE_CASE)
private val SPRITE_HINT = Regex("""sprites?\.vtt(\?|$)""", RegexOption.IGNORE_CASE)
