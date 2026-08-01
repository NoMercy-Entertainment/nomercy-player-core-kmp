// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.text.Layout
import androidx.media3.common.text.Cue
import tv.nomercy.player.core.events.ALIGN_CENTER
import tv.nomercy.player.core.events.ALIGN_END
import tv.nomercy.player.core.events.ALIGN_START
import tv.nomercy.player.core.events.FULL_SIZE
import tv.nomercy.player.core.events.SubtitleCue

// Media3's cues, turned into the library's.
//
// Pure, like [ExoTrackMapper] beside it and for the same reason: everything that
// can be wrong here is wrong silently. A line read as a fraction when it was a
// row count puts a caption at the top of the frame; an alignment left at its
// default puts a right-anchored sign in the middle of the picture. A function
// that can be handed a fabricated Cue is the only way to see either before a
// television does.
public object ExoCueMapper {

    // Media3 reports positions as fractions of the viewport, 0 to 1. The
    // library's cue is in the percentages WebVTT itself is written in, which is
    // also what the layout and both chromes already work in.
    public fun cuesOf(cues: List<Cue>): List<SubtitleCue> = cues.mapNotNull(::cueOf)

    // Null for a cue with no text. Media3 also carries bitmap cues — DVB and
    // PGS subtitles are images, not strings — and a text renderer has nothing to
    // draw for one. Dropping it is honest; an empty string would paint an empty
    // box over the picture at whatever position the image would have had.
    public fun cueOf(cue: Cue): SubtitleCue? {
        val text: CharSequence = cue.text ?: return null
        val plain: String = text.toString()
        if (plain.isEmpty()) return null

        return SubtitleCue(
            text = plain,
            plainText = plain,
            line = linePercentOf(cue),
            align = alignOf(cue.textAlignment),
            size = percentOf(cue.size) ?: FULL_SIZE,
            position = percentOf(cue.position),
        )
    }

    // Only a fractional line converts. Media3's other line type counts text
    // ROWS from the top or bottom of the viewport, which is a different unit
    // from a percentage of it and would land a cue at a fifteenth of the height
    // it asked for. Null hands it back to the layout's own default, which is
    // where a cue with no instruction belongs.
    private fun linePercentOf(cue: Cue): Double? =
        if (cue.lineType == Cue.LINE_TYPE_FRACTION) percentOf(cue.line) else null

    // Cue.DIMEN_UNSET is Float.MIN_VALUE, not zero, so an unset dimension read
    // straight through would be a real position near the left edge.
    private fun percentOf(dimension: Float): Double? =
        if (dimension == Cue.DIMEN_UNSET) null else (dimension * FULL_SIZE).toDouble()

    // Media3 speaks the platform's paragraph alignment rather than WebVTT's.
    // NORMAL and OPPOSITE are relative to the writing direction, which is what
    // `start` and `end` mean, so the two vocabularies line up exactly once the
    // names are matched.
    private fun alignOf(alignment: Layout.Alignment?): String = when (alignment) {
        Layout.Alignment.ALIGN_NORMAL -> ALIGN_START
        Layout.Alignment.ALIGN_OPPOSITE -> ALIGN_END
        else -> ALIGN_CENTER
    }
}
