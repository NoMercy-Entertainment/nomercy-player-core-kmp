// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// What a MediaList reports when it changes.
//
// Two levels on purpose: the specific event says what happened, and the change
// that follows says the list is now different. A view that only redraws
// subscribes to change; a view that animates an insertion needs to know it was
// an insertion and where.

public data class MediaListChange<T>(val items: List<T>)

public data class MediaListAppend<T>(val items: List<T>, val from: Int)

public data class MediaListPrepend<T>(val items: List<T>)

public data class MediaListInsert<T>(val items: List<T>, val index: Int)

// Carries the item and the index it used to be at, because by the time a
// listener runs it is gone from the list and neither can be looked up.
public data class MediaListRemove<T>(val id: String, val index: Int, val item: T)

public data class MediaListMove(val from: Int, val to: Int)

public data class MediaListClear(val previousLength: Int)

// Fired when the cursor moves, whether a caller moved it or a reorder carried
// it. The index is -1 when there is nothing current.
public data class MediaListCurrent<T>(val item: T?, val index: Int)
