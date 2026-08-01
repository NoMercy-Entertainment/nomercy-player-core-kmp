// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// One subtitle cue, normalised across sources.
//
// The web's `SubtitleCue`, field for field, and in core for the same reason it
// is there: a renderer must not have to know whether the line came from an
// engine's own text track, a sidecar .vtt the consumer added, or a parser the
// consumer registered. One shape, one channel, one renderer.
//
// It carries the positioning because WebVTT specifies it and every producer here
// can read it. Media3 reports a line, a position, a size and an alignment on
// every cue it hands over; AVFoundation reports the same through its legible
// output; [tv.nomercy.player.core.cues.VttCueSettings] parses align, line and
// size off the timing line. A cue that lost them on the way to the renderer
// would put every forced sign, every top-of-frame caption and every narrow box
// at the bottom of the picture, centred, over the dialogue it was placed to
// avoid.
public data class SubtitleCue(
    // Renderer-safe inline markup — `<i>`, `<b>`, `<u>` — when the source
    // carried any.
    val text: String,
    // The same content with every tag stripped. What a screen reader is given,
    // and what a renderer with no markup support draws.
    val plainText: String,
    /** WebVTT `line:` (0-100 percent). Null is `line:auto`, which stacks from the bottom. */
    val line: Double? = null,
    /** WebVTT `align:`, normalised to `start`, `center` or `end`. */
    val align: String = ALIGN_CENTER,
    /** WebVTT `size:` — the box's width as a percentage of the safe area. */
    val size: Double = FULL_SIZE,
    /** WebVTT `position:` (0-100 percent). Null is the default implied by [align]. */
    val position: Double? = null,
)

// The three the format allows, after `left`/`right`/`middle` have been folded
// into the canonical spelling. A string rather than an enum because this is the
// vocabulary [tv.nomercy.player.core.cues.VttCueSettings.alignment] already
// speaks, and a second spelling of the same three values in the same library is
// how a mapper starts being needed between two of our own types.
public const val ALIGN_START: String = "start"
public const val ALIGN_CENTER: String = "center"
public const val ALIGN_END: String = "end"

// A full-width box, in the percentages the format is written in.
public const val FULL_SIZE: Double = 100.0
