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
// SEED: the media value-types plan expands this interface with title, sources,
// artwork and the rest. Expanding it is expected; redefining it or moving it to
// another package is not — event payloads across all three libraries reference
// this exact type.
public interface PlaylistItem {
    public val id: String
}
