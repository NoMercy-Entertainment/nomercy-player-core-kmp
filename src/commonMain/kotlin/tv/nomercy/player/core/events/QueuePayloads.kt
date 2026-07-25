// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import tv.nomercy.player.core.media.PlaylistItem

// The queue's shape after a change. A view that only redraws needs this and
// nothing else.
public data class QueueChange(val items: List<PlaylistItem>)

// [from] is where the new items start, so a list can animate the arrival
// without diffing the whole queue.
public data class QueueAppend(val items: List<PlaylistItem>, val from: Int)

public data class QueuePrepend(val items: List<PlaylistItem>)

public data class QueueInsert(val items: List<PlaylistItem>, val index: Int)

// Carries the item and where it was, because by the time a listener runs
// neither can be looked up any more.
public data class QueueRemove(val id: String, val index: Int, val item: PlaylistItem)

public data class QueueMove(val from: Int, val to: Int)

public data class QueueClear(val previousLength: Int)
