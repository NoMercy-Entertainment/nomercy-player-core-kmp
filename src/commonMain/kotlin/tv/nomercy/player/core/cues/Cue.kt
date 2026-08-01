// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

// One cue, carrying whatever its format's parser produced.
//
// The web's core/cues/cue.ts has this generic; this did not, and the narrowing
// was not additive — it was a second, flatter shape (CueEvent: id, startTime,
// endTime, text) standing in for every format at once. That shape stays, for
// the same reason the web keeps SubtitleCue beside Cue<T>: a generic subtitle-
// crossing event has no business caring which parser produced the line. What
// [CueParser] and [tv.nomercy.player.core.media.CueTracker] need is this one,
// because an enhanced-LRC line, a sprite rectangle and a WebVTT cue with
// alignment are three different payloads and CueEvent has room for none of
// them beyond a string.
public data class Cue<T>(
    val start: Double,
    val end: Double,
    val payload: T,
    /** Optional stable id, mirroring the web's `Cue<T>.id`. */
    val id: String? = null,
)

// What every built-in payload carries, whatever else it carries.
//
// A consumer that resolves a parser by URL rather than by id — LyricsPlugin
// asking for "whatever handles this url", not "the LRC parser specifically" —
// cannot know at compile time whether it is holding an [LrcPayload] or a
// [VttSubtitlePayload]. Both are lines of text; that much is safe to read
// without knowing which. The web's LyricsPlugin has the identical shape,
// locally, as `interface LyricPayload { text: string }` — this is that
// contract moved to where every format that could satisfy it already lives, so
// a future format joins by implementing one interface rather than a plugin
// inventing its own each time.
//
// Per-format detail — [LrcPayload.words], [VttSubtitlePayload.alignment] — is
// still there; a consumer that wants it downcasts, the same way a consumer
// reading the web's open `[key: string]: unknown` reaches for a field it knows
// its own format has.
public interface TextPayload {
    public val text: String
}
