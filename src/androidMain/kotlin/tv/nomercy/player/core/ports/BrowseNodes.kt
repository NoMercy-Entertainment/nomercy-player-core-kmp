// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import tv.nomercy.player.core.plugin.BrowseNode

// A browse entry, in the shape Android Auto insists on.
//
// The flags are not decoration. Auto reads isPlayable and isBrowsable to decide
// whether a row opens or starts playing, and a node with neither set is drawn
// and then does nothing when tapped — which is the single most common way a
// browse tree looks finished and is not.
internal fun BrowseNode.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .setIsPlayable(playable)
            .setIsBrowsable(browsable)
            .setMediaType(mediaType())
            .build(),
    )
    .build()

// Auto groups and titles rows by type, and a tree that leaves it unset gets a
// generic treatment on the head unit that looks nothing like the phone.
private fun BrowseNode.mediaType(): Int = when {
    browsable -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    else -> MediaMetadata.MEDIA_TYPE_MIXED
}
