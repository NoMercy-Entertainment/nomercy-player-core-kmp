// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// A stretch of the timeline, in seconds.
//
// Seconds and not milliseconds, matching every other time on this player and the
// media element it grew from. A range that mixed units with time() would be an
// off-by-a-thousand waiting for whoever compares them.
public data class TimeRange(
    val start: Double,
    val end: Double,
) {
    public val duration: Double get() = (end - start).coerceAtLeast(0.0)

    public operator fun contains(seconds: Double): Boolean = seconds >= start && seconds <= end
}

// Whether [seconds] falls inside any of these.
//
// The question a scrubber asks before promising a jump: seeking into a gap
// between two buffered ranges is legal and stalls, and a chrome that knows can
// show the difference.
public fun List<TimeRange>.covers(seconds: Double): Boolean = any { seconds in it }
