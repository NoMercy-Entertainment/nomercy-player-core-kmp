// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.CueEvent

// Turns a subtitle, lyric or sprite file into cues.
//
// A port because the formats are open-ended in a way this library should not
// try to close. VTT and LRC are the ones everyone has; a karaoke format with
// per-syllable timing, a sprite index, a fan-made chapter file are the ones a
// consumer has and this library has never heard of. Registering a parser is how
// they say so, and it means adding a format does not mean forking the player.
public interface CueParser {
    // 'vtt', 'lrc', 'sprite-vtt'. Vendor-prefixed for anything custom —
    // 'fillz:karaoke' — so a consumer's format cannot collide with one this
    // library adds later.
    public val id: String

    // Whether this parser handles the input.
    //
    // Returning true is a commitment: the registry stops looking. A parser that
    // says yes and then cannot parse leaves the caller with an error instead of
    // the next parser's answer, so a doubtful yes is worse than a no.
    public fun canParse(url: String, contentType: String? = null): Boolean

    // Errors propagate. The registry does not swallow them, because a subtitle
    // file that arrived corrupt is worth a visible failure rather than an empty
    // track that looks like a film with no dialogue.
    public fun parse(raw: String, baseUrl: String? = null): List<CueEvent>
}

// Which parser handles what.
//
// Most-recently-registered wins, so a consumer overriding a built-in just
// registers theirs. That is the whole ordering rule and it is the one that makes
// override work without a priority number nobody can choose correctly.
public class CueParserRegistry {

    private val parsers: MutableList<CueParser> = mutableListOf()

    // Re-registering an id replaces the existing entry rather than shadowing it,
    // so a plugin re-registering on every setup does not grow the list forever.
    //
    // atLowestPriority puts it where a built-in belongs: seeded so anything a
    // consumer registers later wins without them having to know what was
    // already there.
    public fun register(parser: CueParser, atLowestPriority: Boolean = false) {
        parsers.removeAll { it.id == parser.id }
        if (atLowestPriority) parsers.add(0, parser) else parsers.add(parser)
    }

    public fun unregister(id: String) {
        parsers.removeAll { it.id == id }
    }

    // Null when nothing matches, rather than a parser that will fail. Whether an
    // unparseable subtitle is an error or a shrug is the caller's call: a
    // missing lyric file is nothing, a missing subtitle the viewer just chose is
    // something.
    public fun resolve(url: String, contentType: String? = null): CueParser? =
        parsers.lastOrNull { it.canParse(url, contentType) }

    public fun findById(id: String): CueParser? = parsers.firstOrNull { it.id == id }

    public fun list(): List<String> = parsers.map { it.id }

    public fun dispose() {
        parsers.clear()
    }
}
