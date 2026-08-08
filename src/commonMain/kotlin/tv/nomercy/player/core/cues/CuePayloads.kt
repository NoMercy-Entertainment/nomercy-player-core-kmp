// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

/**
 * A cue entering or leaving, and which tracker noticed.
 *
 * [trackerId] rather than a bare cue, because a player runs several trackers at
 * once — subtitles, chapters, lyrics, sprites — and a listener told only "a cue
 * started" has to guess which list it came from. Guessing wrong draws a chapter
 * title where a subtitle belongs.
 */
public data class CueEventPayload(
    val trackerId: String,
    val cue: CueWindow,
)

/** A cue's span and whatever it carries. */
public data class CueWindow(
    val start: Double,
    val end: Double,
    val payload: Any?,
)

/**
 * One word of a lyric line, with when it lands.
 *
 * Word timings are what make a lyric highlight follow the singer rather than
 * jump a line at a time, and they are per word because that is the resolution
 * the format carries.
 */
public data class LrcWordCue(
    val start: Double,
    val text: String,
)
