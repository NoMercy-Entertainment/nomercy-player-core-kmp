// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Where the buffer reaches, out of the ranges an engine reports.
//
// One number out of a list, and the reason it is not simply the last range's end
// is what a seek does to the list. Jump back an hour in a long film and the
// engine keeps what it had: the ranges become [0, 90] and [3500, 3600] while the
// playhead sits at 5. A bar drawn from the last end promises an hour of buffer
// over a stretch that holds none, so the viewer drags into it and the picture
// stalls on a bar that said it would not.
//
// Four answers, and each one is a different sentence about the same list:
//
//  * inside a range — the buffer reaches that range's end, and no further,
//    because the next range is on the far side of a hole.
//  * no ranges at all — nothing is buffered. Not the position: an engine that
//    has read no data has not buffered the frame it is showing either.
//  * past every range — the last end, which is where the data stops.
//  * in a hole, or before the first range — the position itself, which draws no
//    buffer ahead of the playhead. This is the honest answer while a seek into
//    unbuffered territory is being served.
//
// Ported from the web player's own walk in `html5.ts`. It lives beside
// [MediaBackend] rather than in the video library because that is where the
// contract it answers lives: `buffered()` takes this as its default, so an
// engine that can report ranges gets the frontier right without implementing
// the walk again — which is how one engine came to report 3600 for the list
// above while the other two did not.
public fun bufferedFrontier(ranges: List<TimeRange>, currentTime: Double): Double {
    // The FIRST range holding the position, matching the web's loop rather than
    // the widest or the nearest. Ranges arrive in order and do not overlap, so
    // there is only ever one — and picking differently would be a rule the
    // oracle does not have.
    val holding: TimeRange? = ranges.firstOrNull { currentTime in it }
    if (holding != null) return holding.end

    val last: TimeRange = ranges.lastOrNull() ?: return 0.0

    return if (currentTime > last.end) last.end else currentTime
}
