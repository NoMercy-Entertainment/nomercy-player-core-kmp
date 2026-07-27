// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.nomercy.player.core.plugin.BrowseNode
import tv.nomercy.player.core.plugin.EmptyBrowseTree
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What a car is handed when it asks what this player has.
//
// On a device because MediaItem and MediaMetadata are Android types that a host
// JVM only has stubs of. The flags are the whole test: Auto reads them to decide
// whether a row opens or plays, and a node with neither is drawn and then does
// nothing when tapped — a browse tree that looks finished and is not.
class BrowseNodesTest {

    @Test
    fun aFolderIsMarkedBrowsableAndNotPlayable() {
        val item: MediaItem = BrowseNode(
            id = "library-films",
            title = "Films",
            browsable = true,
        ).toMediaItem()

        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
    }

    @Test
    fun anEpisodeIsMarkedPlayableAndNotBrowsable() {
        val item: MediaItem = BrowseNode(
            id = "episode-1",
            title = "Pilot",
            playable = true,
        ).toMediaItem()

        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
    }

    @Test
    fun anAlbumCanBeBothAtOnce() {
        // The case a single kind field cannot express, and a real one: an album
        // opens to its tracks and also plays from the top.
        val item: MediaItem = BrowseNode(
            id = "album-1",
            title = "Blade Runner 2049",
            playable = true,
            browsable = true,
        ).toMediaItem()

        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(true, item.mediaMetadata.isBrowsable)
    }

    @Test
    fun theIdSurvivesSoASelectionCanBeResolved() {
        // What comes back when a viewer taps a row. An item whose id was lost
        // is a tap that reaches the service with nothing to look up.
        val item: MediaItem = BrowseNode(id = "episode-42", title = "x", playable = true).toMediaItem()

        assertEquals("episode-42", item.mediaId)
    }

    @Test
    fun titleAndSubtitleAndArtworkAllReachTheHeadUnit() {
        val item: MediaItem = BrowseNode(
            id = "1",
            title = "Blade Runner 2049",
            subtitle = "Denis Villeneuve",
            artworkUrl = "https://cdn.example.test/poster.jpg",
            playable = true,
        ).toMediaItem()

        assertEquals("Blade Runner 2049", item.mediaMetadata.title.toString())
        assertEquals("Denis Villeneuve", item.mediaMetadata.subtitle.toString())
        assertEquals(
            "https://cdn.example.test/poster.jpg",
            item.mediaMetadata.artworkUri.toString(),
        )
    }

    @Test
    fun aFolderCarriesAFolderTypeSoAutoDrawsItAsOne() {
        // Auto groups and titles rows by media type, and a tree that leaves it
        // unset gets a generic treatment on the head unit that looks nothing
        // like the phone.
        val folder: MediaItem = BrowseNode(id = "f", title = "Films", browsable = true).toMediaItem()

        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, folder.mediaMetadata.mediaType)
    }

    @Test
    fun aPlayerWithNoCatalogueOffersAnEmptyLibraryRatherThanAnError() {
        // A car connecting to a player nobody gave a catalogue to should find
        // an empty library. Empty is true; an error is a bug report.
        val tree = EmptyBrowseTree()

        runBlocking {
            val root: BrowseNode = tree.root()

            assertTrue(root.browsable, "the root was not browsable, so the car cannot open it")
            assertEquals(emptyList(), tree.children(root.id))
        }
    }
}
