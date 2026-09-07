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
import tv.nomercy.player.core.plugin.EmptyBrowseTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The seam a car reads its catalogue through.
//
// A tree that is built and never registered is the failure mode this port keeps
// hitting, so what gets asserted here is the registration, not the contents.
//
// The install is process-global by necessity — Android builds the session from
// a plugin with nowhere to pass a tree in — so these tests each install what
// they read rather than leaning on the order they happen to run in.
class InstalledBrowseTreeTest {

    private class Catalogue(private val entries: List<BrowseNode>) : BrowseTreeProvider {
        override suspend fun root(): BrowseNode =
            BrowseNode(id = ROOT, title = "My library", browsable = true)

        override suspend fun children(parentId: String): List<BrowseNode> = entries
    }

    @Test
    fun theInstalledTreeIsTheOneReadBack() = runTest {
        PlatformEnvironment.installBrowseTree(
            Catalogue(listOf(BrowseNode(id = "album-1", title = "An album", browsable = true))),
        )

        assertEquals(ROOT, PlatformEnvironment.browseTree.root().id)
        assertEquals(
            listOf("album-1"),
            PlatformEnvironment.browseTree.children(ROOT).map { it.id },
        )
    }

    @Test
    fun installingASecondTreeReplacesTheFirstRatherThanAddingToIt() = runTest {
        PlatformEnvironment.installBrowseTree(
            Catalogue(listOf(BrowseNode(id = "old", title = "Old"))),
        )
        PlatformEnvironment.installBrowseTree(
            Catalogue(listOf(BrowseNode(id = "new", title = "New"))),
        )

        assertEquals(listOf("new"), PlatformEnvironment.browseTree.children(ROOT).map { it.id })
    }

    // What an app that supplies no catalogue hands a car: a root it can open,
    // with nothing under it. A root that is not browsable is a player the head
    // unit draws once and never enters, which reads as a broken app rather than
    // an empty one.
    @Test
    fun theCatalogueAnAppSuppliesNothingForIsEmptyAndStillOpenable() = runTest {
        val fallback: BrowseTreeProvider = EmptyBrowseTree()

        assertEquals(EmptyBrowseTree.ROOT_ID, fallback.root().id)
        assertTrue(fallback.root().browsable)
        assertTrue(fallback.children(EmptyBrowseTree.ROOT_ID).isEmpty())
    }

    private companion object {
        const val ROOT: String = "my-root"
    }
}
