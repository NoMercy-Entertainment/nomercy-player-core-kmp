// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.chapters

import tv.nomercy.player.core.media.Chapter
import tv.nomercy.player.core.media.ChapterGapFill
import tv.nomercy.player.core.media.fillChapterGaps

// The one seam every chapter path goes through.
//
// Chapters arrive from more than one place — a sidecar `chapters.vtt`, and
// whatever a playlist carries inline — and they all need the same two things
// done to them: gaps filled against the item's real duration, and the result
// announced once. Doing that at each source is how two sources come to disagree
// about whether the run-up to the first chapter is covered.
//
// Duration is the awkward part. It is not known when the chapters arrive: the
// engine reports it after the container is read, and it can change again on a
// live item or a stream whose length was estimated. So the fill has to be
// re-runnable, and re-running must not stack fillers on top of fillers — which
// is why the raw list is kept and re-derived from rather than mutated.
public class ChapterController(
    private val makeFillerTitle: () -> String = { DEFAULT_FILLER_TITLE },
) {

    // What the source actually gave, before any filling. Kept so a duration
    // change re-derives rather than filling an already-filled list.
    private var source: List<Chapter> = emptyList()

    private var filled: List<Chapter> = emptyList()

    private var duration: Double = 0.0

    public fun chapters(): List<Chapter> = filled

    // Returns whether anything changed, so a caller emits once rather than on
    // every tick. A chapters event on every time update is a chrome rebuilding
    // its menu several times a second.
    public fun ingest(chapters: List<Chapter>): Boolean {
        source = chapters.sortedBy { it.startTime }

        return refill()
    }

    // Called when the engine reports a length, and again if it changes.
    //
    // Rejects a length that is not one. Engines report zero before a container
    // is read and libVLC reports -1 for "not yet", and accepting either would
    // take a list that is already covered back to its raw form: refill derives
    // from the source every time, so a duration that reads as unknown strips the
    // run-up filler out of a menu the viewer is looking at.
    //
    // Whether anything changed is refill's answer alone. An earlier version also
    // skipped a duration that had not moved, which sounds like the same question
    // but is not — a Chapter carries a start and no end, so the duration never
    // reaches the output list and two identical durations were already going to
    // produce one identical list. The second guard could only ever agree, and
    // what it did instead was answer first: durationChanged(0.0) on a fresh
    // controller returned through it rather than through the check above, which
    // left that check free to be deleted with every test still green.
    public fun durationChanged(seconds: Double): Boolean {
        if (seconds <= 0.0) return false

        duration = seconds
        return refill()
    }

    public fun clear() {
        source = emptyList()
        filled = emptyList()
        duration = 0.0
    }

    private fun refill(): Boolean {
        // No duration yet means no filling at all, which is fillChapterGaps'
        // rule rather than this one's: an item whose length nobody knows is a
        // live stream or metadata that has not arrived, and there is no end to
        // fill up to. The raw list is still stored and announced, so a viewer
        // gets a working menu now and a covered one when the engine reports how
        // long the item is.
        val result: ChapterGapFill = fillChapterGaps(source, duration, makeFillerTitle)
        val changed: Boolean = result.chapters != filled

        filled = result.chapters
        return changed
    }
}

// Named rather than left to the caller, because every caller would pick a
// different word for the same thing and a viewer would see two.
private const val DEFAULT_FILLER_TITLE = "Chapter"
