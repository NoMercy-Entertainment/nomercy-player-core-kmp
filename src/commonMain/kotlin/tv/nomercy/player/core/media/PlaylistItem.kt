// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// A queue item. Kotlin mirror of the web BasePlaylistItem.
//
// Three fields, and each one is here because the core genuinely needs it: the
// id identifies the item across a reorder, the url is what gets loaded, and the
// title is what a chrome shows before any metadata arrives. Artwork, duration,
// per-library subtypes and the rest belong to the libraries that use them.
public interface PlaylistItem {
    public val id: String
    public val url: String
    public val title: String?
}
