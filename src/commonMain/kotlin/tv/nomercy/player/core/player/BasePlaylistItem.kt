// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

/**
 * The smallest thing a playlist entry can be.
 *
 * Four fields, and only [id] is required. A consumer's item is its own type
 * carrying a season, a chapter list and a colour palette; the player needs to
 * tell one entry from another, show something while it loads, and know where to
 * fetch it. Demanding more would make every consumer reshape their catalogue to
 * suit a library.
 */
public interface BasePlaylistItem {
    public val id: String
    public val title: String? get() = null
    public val image: String? get() = null
    public val url: String? get() = null
}
