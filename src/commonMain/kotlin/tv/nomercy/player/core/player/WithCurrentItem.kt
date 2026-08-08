// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

/**
 * Reading the item the player is on, and moving it.
 *
 * One name for both, as the web has it: `item()` reads and `item(target)` moves.
 * Two names would let a caller read from one and write to the other and never
 * find out — which is exactly what happens when a `currentItem` getter and a
 * `setItem` disagree about whether an advance has landed yet.
 *
 * Four ways to name the target, because all four are how a chrome actually asks:
 * the row that was clicked, a deep link's id, a keyboard jump to an index, and
 * "the next unwatched one".
 */
public interface WithCurrentItem<T : BasePlaylistItem> {

    /** What is loaded, or null before anything is. */
    public fun item(): T?

    public fun item(target: T)

    public fun item(id: String)

    public fun item(index: Int)

    public fun item(match: (T) -> Boolean)
}
