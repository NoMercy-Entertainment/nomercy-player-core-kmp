// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

/**
 * Something to play, for a caller who has nothing to add to it.
 *
 * [PlaylistItem] is an interface because a host has its own item — an episode
 * with a season number, a track with an album — and the player must carry it
 * rather than make the host translate into a shape the library invented. That
 * is right, and it left a hole: a caller with only a url and a title had to
 * declare a type to hold them. Every testbed, sample and test in this
 * repository has declared that type separately, and Swift cannot declare it at
 * all without conforming to an exported protocol by hand.
 *
 * So this is the plain one. It adds nothing to the interface and exists so that
 * "play this url" is a line rather than a file.
 */
public data class MediaItem(
    override val id: String,
    override val url: String,
    override val title: String? = null,
) : PlaylistItem
