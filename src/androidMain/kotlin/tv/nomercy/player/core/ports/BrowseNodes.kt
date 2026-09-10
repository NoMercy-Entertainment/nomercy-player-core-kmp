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
import tv.nomercy.player.core.plugin.BrowseTreeProvider
import tv.nomercy.player.core.plugin.EmptyBrowseTree

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

// Where the catalogue a car asks for comes from, installed the same way and
// read the same way as [MediaNotificationBranding] beside it.
//
// The session that answers a car is built by [Media3SystemTransport], which
// Android constructs through a plugin with no place to pass a tree in; this is
// the same forced-static-seam PlatformEnvironment already solves for the
// Context and the notification branding, so it gets the same shape rather than
// a fourth mechanism.
@Volatile
private var installedBrowseTree: BrowseTreeProvider? = null

public fun PlatformEnvironment.installBrowseTree(provider: BrowseTreeProvider) {
    installedBrowseTree = provider
}

// Never null: a car that connects to an app which supplied no catalogue finds
// an empty library rather than an error, which is [EmptyBrowseTree]'s whole
// reason for existing. Bluetooth AVRCP needs the service to be discoverable,
// not to have anything in it.
public val PlatformEnvironment.browseTree: BrowseTreeProvider
    get() = installedBrowseTree ?: FALLBACK_BROWSE_TREE

private val FALLBACK_BROWSE_TREE: BrowseTreeProvider = EmptyBrowseTree()

// The window a browser asked for, out of everything the tree knows.
//
// A browser names a page size of Int.MAX_VALUE when it wants the whole list,
// which is why the arithmetic is done in Long and clamped back: computed in Int
// the end index overflows negative and subList throws on exactly the request
// that asked for everything.
internal fun <T> List<T>.browsePage(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from: Long = page.toLong() * pageSize.toLong()
    if (from >= size) return emptyList()
    val until: Long = minOf(from + pageSize.toLong(), size.toLong())
    return subList(from.toInt(), until.toInt())
}

// The other half of browsing: what happens when a car taps one of these.
//
// The player this library drives is a transport bridge, not a queue — the app
// owns what plays and in what order, exactly as it does when the tap comes from
// its own screens. So a browser's selection is handed back rather than acted on
// here: the core knows an id was chosen, and only the app knows what that id
// means.
@Volatile
private var installedBrowseSelection: ((String) -> Unit)? = null

public fun PlatformEnvironment.installBrowseSelection(onChosen: (mediaId: String) -> Unit) {
    installedBrowseSelection = onChosen
}

public val PlatformEnvironment.browseSelection: ((String) -> Unit)?
    get() = installedBrowseSelection
