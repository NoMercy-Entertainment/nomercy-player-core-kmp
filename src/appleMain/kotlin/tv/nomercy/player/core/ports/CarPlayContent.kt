// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.plugin.BrowseNode
import tv.nomercy.player.core.plugin.BrowseTreeProvider

// What a car is offered to browse.
//
// The now-playing screen in a car needs nothing from here: it is fed by the same
// metadata and command centre the lock screen already uses, so it works the
// moment a session exists. What a car adds is a list, and the list is the same
// browse tree everything else reads.
//
// The templates and the scene belong to the application. A library cannot own
// them: they arrive through a UIScene the system connects, they need an
// entitlement granted to the shipping application, and there is no way to build
// one without being that application. What the library owns is the answer to
// what should be in the list.
public class CarPlayContent(
    private val tree: BrowseTreeProvider,

    // Supplied by the caller because the system decides it, not us. CarPlay
    // truncates a list according to the vehicle and whether it is moving, and
    // there is no constant in the SDK to read. A library inventing a number
    // would be wrong in one car and wrong differently in the next.
    private val limit: Int? = null,
) {

    public suspend fun root(): BrowseNode = tree.root()

    // Entries a car can do something with. A node that neither plays nor opens
    // is a row that does nothing when tapped, and a driver tapping a dead row
    // looks at the screen for longer than they should.
    public suspend fun listing(parentId: String): List<BrowseNode> {
        val usable: List<BrowseNode> = tree.children(parentId).filter { it.playable || it.browsable }

        return if (limit == null) usable else usable.take(limit)
    }
}
