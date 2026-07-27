// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import tv.nomercy.player.core.plugin.Plugin

// The half of key handling that is the same everywhere.
//
// A television and a desktop disagree about what the arrow keys do and agree
// about everything else, so the difference belongs in a subclass rather than in
// a branch. What lives here is the table, the dispatch, and the fact that
// defaults are installed when the plugin is.
//
// Open rather than sealed, because a consumer with a remote nobody has seen
// before should be able to add to this rather than fork it.
public abstract class KeyHandlerPlugin<O : Any>(
    nowMs: () -> Long,
) : Plugin<O>() {

    protected val bindings: KeyBindingTable = KeyBindingTable(nowMs)

    // Installed at registration rather than at the first press, so a chrome can
    // ask what is bound in order to draw a help sheet before anybody has pressed
    // anything.
    override fun use() {
        addDefaults()
    }

    protected abstract fun addDefaults()

    // Whether the press was ours. A platform passes this back to the system when
    // it is false, which is what leaves the volume keys and the back button
    // working.
    public open fun handle(combo: KeyCombo): Boolean = bindings.handle(combo)

    public open fun handle(key: PlayerKey): Boolean = bindings.handle(key)
}
