// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.plugin.BrowseNode
import tv.nomercy.player.core.plugin.BrowseTreeProvider
import kotlin.test.Test
import kotlin.test.assertEquals

// The list a car is shown.
class CarPlayContentTest {

    private class Catalogue(private val entries: List<BrowseNode>) : BrowseTreeProvider {
        override suspend fun root(): BrowseNode =
            BrowseNode(id = "root", title = "Library", browsable = true)

        override suspend fun children(parentId: String): List<BrowseNode> = entries
    }

    private val mixed = listOf(
        BrowseNode(id = "a", title = "An album", browsable = true),
        BrowseNode(id = "b", title = "A heading", subtitle = "not tappable"),
        BrowseNode(id = "c", title = "A track", playable = true),
    )

    @Test
    fun aRowThatDoesNothingWhenTappedIsNotOffered() = runTest {
        // A driver tapping a dead row looks at the screen for longer than they
        // should, which is the entire reason this interface is restricted.
        val content = CarPlayContent(Catalogue(mixed))

        val listing: List<BrowseNode> = content.listing("root")

        assertEquals(listOf("a", "c"), listing.map { it.id })
    }

    @Test
    fun withNoLimitEverythingUsableIsOffered() = runTest {
        // The system truncates according to the vehicle and there is no constant
        // to read, so a library inventing a number would be wrong in one car and
        // wrong differently in the next.
        val many = List(40) { BrowseNode(id = "$it", title = "Track $it", playable = true) }
        val content = CarPlayContent(Catalogue(many))

        assertEquals(40, content.listing("root").size)
    }

    @Test
    fun aLimitTheSceneReportsIsHonoured() = runTest {
        val many = List(40) { BrowseNode(id = "$it", title = "Track $it", playable = true) }
        val content = CarPlayContent(Catalogue(many), limit = 12)

        assertEquals(12, content.listing("root").size)
    }

    @Test
    fun theLimitCountsOnlyTheRowsThatSurvivedTheFilter() = runTest {
        // Counting before filtering hands a car a list shorter than it asked for
        // and blames the catalogue for being empty.
        val padded = List(20) { BrowseNode(id = "dead$it", title = "Heading") } +
            List(20) { BrowseNode(id = "live$it", title = "Track", playable = true) }
        val content = CarPlayContent(Catalogue(padded), limit = 5)

        val listing: List<BrowseNode> = content.listing("root")

        assertEquals(5, listing.size)
        assertEquals(listOf("live0", "live1", "live2", "live3", "live4"), listing.map { it.id })
    }

    @Test
    fun theRootIsWhateverTheCatalogueSays() = runTest {
        // Not renamed or wrapped. A car showing a different title from the phone
        // reads as a different library.
        val content = CarPlayContent(Catalogue(mixed))

        assertEquals("Library", content.root().title)
    }
}
