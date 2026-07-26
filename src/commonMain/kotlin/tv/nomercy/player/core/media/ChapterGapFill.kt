// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// What came back, and whether anything was added.
//
// The flag matters because the caller re-emits on change and a caller that
// re-emitted unconditionally would announce a new chapter list on every time
// update.
public data class ChapterGapFill(
    val chapters: List<Chapter>,
    val changed: Boolean,
)

// A quarter of a second. Below this a gap is a rounding difference between a
// scanner and a container, not a hole a viewer can land in.
public const val CHAPTER_GAP_EPSILON_SECONDS: Double = 0.25

// Makes a chapter list cover the whole item.
//
// The web player fills three kinds of hole — before the first chapter, between
// two, and after the last. Only the first exists here, and that is the model
// rather than an omission: a Chapter has a start and no end, so chapter N runs
// until N+1 begins and the last runs to the end of the item. A gap between two
// chapters cannot be represented, which means it cannot occur.
//
// What can occur is a scan that starts its first chapter partway in — an opening
// credits sequence nobody labelled. Without a filler, a viewer scrubbing into
// the first thirty seconds is inside no chapter at all, and a chrome showing the
// current chapter title shows nothing.
public fun fillChapterGaps(
    chapters: List<Chapter>,
    duration: Double,
    makeFillerTitle: () -> String,
): ChapterGapFill {
    // Re-derived from what the source actually gave, so calling this again after
    // a duration update does not stack fillers on top of fillers.
    val original: List<Chapter> = chapters.filterNot { it.synthetic }

    // Three ways there is nothing to do, and none of them is a hole:
    // an item without chapters, where inventing one whole-length chapter would
    // draw a chapter bar for a film that has none; a duration nobody knows,
    // which is a live stream or metadata that has not arrived, so there is no
    // end to fill up to; and a list holding nothing the source gave, where
    // filling would present an invented chapter as the item's own.
    val nothingToDo: Boolean = original.isEmpty() || !duration.isFinite() || duration <= 0.0
    if (nothingToDo) return ChapterGapFill(chapters, changed = false)

    val ordered: List<Chapter> = original.sortedBy { it.startTime }
    val first: Chapter = ordered.first()
    if (first.startTime <= CHAPTER_GAP_EPSILON_SECONDS) {
        // Already covered, and the incoming list may have carried fillers that
        // are no longer needed — so this still returns the derived list.
        return ChapterGapFill(ordered, changed = ordered != chapters)
    }

    val filled: List<Chapter> = listOf(
        Chapter(startTime = 0.0, title = makeFillerTitle(), synthetic = true),
    ) + ordered

    return ChapterGapFill(filled, changed = true)
}
