// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// One entry in the list a car, a watch or a voice assistant shows.
//
// Deliberately not a playlist item. A browse tree has folders in it, and a
// folder has no url; what these share with a queue item is a title and an
// identity, and modelling them as the same thing means every consumer answering
// "what url does this folder play" for something that never plays.
public data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    // Both, because a node can be either or both: an album opens and also plays.
    // A single kind field would force a choice the format does not make.
    val playable: Boolean = false,
    val browsable: Boolean = false,
)

// Where the entries come from.
//
// The core has no idea what a viewer's library contains — that is the app's,
// and the shape of it is a product decision rather than a player one. What the
// core owns is the fact that a car will ask, and that the answer has to arrive
// without blocking the thread the car asked on.
public interface BrowseTreeProvider {

    public suspend fun root(): BrowseNode

    public suspend fun children(parentId: String): List<BrowseNode>
}

// What a consumer that has not supplied a tree offers: a root and nothing in it.
//
// A car connecting to a player with no browse tree should find an empty library
// rather than an error. Empty is a true statement about a player nobody has
// given a catalogue to; an error is a bug report.
public open class EmptyBrowseTree(
    private val rootTitle: String = "NoMercy",
) : BrowseTreeProvider {

    override suspend fun root(): BrowseNode = BrowseNode(
        id = ROOT_ID,
        title = rootTitle,
        browsable = true,
    )

    override suspend fun children(parentId: String): List<BrowseNode> = emptyList()

    public companion object {
        public const val ROOT_ID: String = "nomercy-root"
    }
}
