// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import tv.nomercy.player.core.media.PlaylistItem

// One immutable snapshot of everything a chrome needs to render the player.
//
// The web has no single type like this: it exposes the same information through
// individual accessors (phase(), playState(), volume(), item(), …). Native
// consumers collect a StateFlow instead of wiring twenty listeners, and a flow
// needs one value, so this aggregates those accessors rather than transcribing
// a web type. Fields are named after the accessors they came from.
//
// Times are seconds, matching the web contract. Volume is 0..100, not 0..1.
public data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val playState: PlayState = PlayState.IDLE,
    val setupState: SetupState = SetupState.NOT_SETUP,
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val buffered: Double = 0.0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val volumeState: VolumeState = VolumeState.UNMUTED,
    val playbackRate: Double = 1.0,
    val item: PlaylistItem? = null,
    // -1, not 0: an empty or un-started queue has no current item, and 0 would
    // claim the first one is playing.
    val index: Int = -1,
    val queueLength: Int = 0,
    val repeatState: RepeatState = RepeatState.OFF,
    val shuffleState: ShuffleState = ShuffleState.OFF,
    val bufferState: BufferState = BufferState.IDLE,
    val networkState: NetworkState = NetworkState.ONLINE,
    val castState: CastState = CastState.UNAVAILABLE,
)
