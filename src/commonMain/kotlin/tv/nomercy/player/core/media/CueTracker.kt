// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import tv.nomercy.player.core.cues.Cue

/**
 * Which cues a time is inside, and when that changed.
 *
 * Core rather than the music package, because the answer is the same for a
 * lyric line, a subtitle and a chapter marker: a list of spans and a position.
 * The web exports it from core for the same reason, and a second copy in the
 * music package would be a second set of edge cases to get right.
 *
 * Generic on T, matching [tv.nomercy.player.core.ports.CueParser]: a tracker
 * over `Cue<LrcPayload>` carries word timing all the way to a karaoke display,
 * where the old CueEvent-only tracker could not carry anything past a string.
 *
 * Crossings rather than state. A caller asking "what is showing" can read
 * [active] any time it likes; what it cannot work out for itself is the moment
 * a line arrived or left, because that needs the previous position as well as
 * this one. So [advanceTo] returns exactly that, and returns nothing when
 * nothing changed — a tracker that reported the same line on every time update
 * would make a consumer dedupe what this already knows.
 */
public class CueTracker<T>(cues: List<Cue<T>> = emptyList()) {

    // Sorted on the way in. A parser is not required to emit in order, and a
    // caller that had to sort first would be a caller who forgets to.
    private var ordered: List<Cue<T>> = cues.sortedBy { it.start }

    private var current: Set<Cue<T>> = emptySet()

    /** The cues the last [advanceTo] was inside. */
    public val active: List<Cue<T>> get() = current.sortedBy { it.start }

    /** The first active cue, which is the one a single-line display shows. */
    public val line: Cue<T>? get() = active.firstOrNull()

    public fun cues(): List<Cue<T>> = ordered

    /**
     * Replace the cues and forget where we were.
     *
     * Forgetting is the point: the next [advanceTo] reports every cue it lands
     * in as entered. Keeping the old set would leave a line from the previous
     * track marked active and never report it leaving, because the cue it would
     * have exited is no longer in the list.
     */
    public fun load(cues: List<Cue<T>>) {
        ordered = cues.sortedBy { it.start }
        current = emptySet()
    }

    /**
     * Move to [seconds] and report what changed.
     *
     * Both halves, in one call. A seek can leave one cue and enter another at
     * the same instant, and two separate queries would let a consumer draw the
     * gap between them.
     */
    public fun advanceTo(seconds: Double): CueCrossing<T> {
        // Half-open on purpose: a cue ending exactly where the next begins must
        // not have both showing for one frame. Closed at both ends is the
        // classic way to get a flicker between every pair of lines.
        val now: Set<Cue<T>> = ordered.filter { seconds >= it.start && seconds < it.end }.toSet()

        val entered: List<Cue<T>> = now.filterNot { it in current }.sortedBy { it.start }
        val exited: List<Cue<T>> = current.filterNot { it in now }.sortedBy { it.start }

        current = now
        return CueCrossing(entered = entered, exited = exited)
    }

    public fun reset() {
        current = emptySet()
    }
}

/** What changed at a position. Both lists are empty when nothing did. */
public data class CueCrossing<T>(
    val entered: List<Cue<T>> = emptyList(),
    val exited: List<Cue<T>> = emptyList(),
) {
    public val changed: Boolean get() = entered.isNotEmpty() || exited.isNotEmpty()
}
