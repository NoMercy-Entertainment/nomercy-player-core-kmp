// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// A named stretch of an item. Chapters in a film, tracks in an audiobook, parts
// of a long mix — the same shape serves all three, which is why this is in core
// rather than in the video library.
//
// [startTime] is where it begins. There is no end: a chapter ends where the next
// one starts, and carrying both invites the two to disagree. The last one runs
// to the end of the item, which only the player knows.
public data class Chapter(
    val startTime: Double,
    val title: String,
    val imageUrl: String? = null,
)

// The chapters of one item, in order, with a cheap answer to "which one is
// playing".
//
// Built once per item rather than searched per time update: a time update
// arrives several times a second and a linear scan through a long audiobook's
// chapter list on each one is real work for a question whose answer usually has
// not changed.
public class ChapterTrack(chapters: List<Chapter>) {

    // Sorted and de-duplicated on construction. A server that emits them out of
    // order, or twice, should not produce a scrubber that jumps backwards.
    public val chapters: List<Chapter> = chapters
        .distinctBy { it.startTime }
        .sortedBy { it.startTime }

    public fun isEmpty(): Boolean = chapters.isEmpty()

    public fun size(): Int = chapters.size

    // The chapter containing [time], or null before the first one starts.
    //
    // Null rather than the first chapter: an item whose first chapter begins at
    // 90 seconds has ninety seconds that belong to no chapter, and claiming
    // otherwise would put a title on screen over a cold open.
    public fun at(time: Double): Chapter? = chapters.lastOrNull { it.startTime <= time }

    public fun indexAt(time: Double): Int = chapters.indexOfLast { it.startTime <= time }

    // Where the chapter after [time] begins, for a skip button. Null at the last
    // chapter, which is the caller's cue to skip to the end of the item instead.
    public fun nextStart(time: Double): Double? = chapters.firstOrNull { it.startTime > time }?.startTime

    // Where to go when a viewer presses previous.
    //
    // Within [restartWindow] seconds of the current chapter's start it goes to
    // the one before, and after that it restarts the current one — the
    // behaviour every music player has, because pressing previous mid-chapter
    // almost always means "start this again".
    public fun previousStart(time: Double, restartWindow: Double = DEFAULT_RESTART_WINDOW): Double? {
        val current: Chapter = at(time) ?: return null
        if (time - current.startTime > restartWindow) return current.startTime
        return chapters.lastOrNull { it.startTime < current.startTime }?.startTime
    }

    public companion object {
        public const val DEFAULT_RESTART_WINDOW: Double = 3.0
    }
}
