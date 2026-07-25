// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState

public data class RateChange(val rate: Double)

public data class RepeatChange(val state: RepeatState)

public data class ShuffleChange(val state: ShuffleState)

// The current item is close enough to the end that whoever is listening has
// time to do something about it — preload the next one, start a crossfade, ask
// whether to play the next episode.
//
// [remaining] is seconds, and it is what listeners act on. The item is carried
// too because by the time a preloader finishes the cursor may have moved.
public data class ItemEndingSoon(
    val item: PlaylistItem?,
    val remaining: Double,
)
