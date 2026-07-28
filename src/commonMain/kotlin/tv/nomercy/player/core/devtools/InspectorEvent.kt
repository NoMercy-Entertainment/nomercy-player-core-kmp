// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

// One line of the stream.
//
// [sequence] counts every event the inspector ever saw, not the position in the
// buffer. Once the buffer has started dropping its oldest entries the position
// stops meaning anything, and "did I miss one" is exactly the question somebody
// reading a debug stream is asking.
//
// [atMs] is milliseconds since the inspector attached rather than a wall clock.
// A debug stream is read for its gaps — the two seconds between the seek and
// the first frame — and a timestamp you have to subtract in your head to see
// them is a timestamp doing half its job.
//
// [summary] is the payload as one short string. Not the payload itself: holding
// it would keep whatever it references alive for as long as the buffer does,
// which for a debug tool left running is a leak with a friendly name.
public data class InspectorEvent(
    val sequence: Long,
    val atMs: Long,
    val name: String,
    val summary: String,
)
