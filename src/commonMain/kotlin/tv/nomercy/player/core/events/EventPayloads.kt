// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerPhase

// Payload for play and pause. Mirror of the web ActionOptions.source: who asked
// for the action, so a listener can tell a UI click from a Connect handoff from
// an autoplay chain.
public data class PlaySource(val source: String? = null)

// Payload for phase. Carries both ends of the move, because a listener that
// only knows where the player arrived cannot tell a pause from a stall.
public data class PhaseChange(
    val from: PlayerPhase,
    val to: PlayerPhase,
)

// Payload for time. Mirror of the web TimeState core fields. Seconds, not
// milliseconds, matching the web contract and the HTML media element.
public data class TimeUpdate(
    val time: Double,
    val duration: Double,
    val percentage: Double,
)

// Everything a progress bar needs, from one call at one instant.
//
// The derived fields are computed here rather than left to each chrome, because
// the two of them that look trivial are not: remaining goes negative when a
// backend reports a position past a stale duration, and percentage divides by a
// duration that is zero for the whole of a live stream.
public data class TimeState(
    val time: Double,
    val duration: Double,
    val buffered: Double,
    val remaining: Double,
    val percentage: Double,
)

// Payload for item. The item is nullable because the cursor legitimately points
// past the end of an exhausted queue.
public data class ItemChange(
    val item: PlaylistItem?,
    val index: Int,
)

// Payload for stream:error. Non-fatal means the stream recovered on its own;
// only fatal errors have stopped playback.
public data class StreamError(
    val details: String,
    val fatal: Boolean,
)
