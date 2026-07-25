// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// The regions of the player's chrome a plugin is allowed to put something in.
//
// A closed set on purpose. An open one would mean every chrome implementation
// has to handle names it has never heard of, and a plugin targeting a slot no
// surface renders would just silently not appear.
public enum class ChromeSlot {
    TopBar,
    Transport,
    Scrubber,
    SettingsMenu,
    Overlay,
    SidePanel,
    Background,
}

// One piece of UI a plugin offers to a slot.
//
// Declared on the manifest rather than discovered, so a chrome knows what it
// will be asked to render before any plugin code runs — no reflection, and no
// surprise contribution appearing mid-playback.
//
// The rendering itself is not here: commonMain has no UI. A Compose or SwiftUI
// chrome looks up the plugin by id and asks it for the content, and this
// describes where that content goes and who wins when two plugins want the same
// place.
public interface ChromeContribution {
    public val slot: ChromeSlot

    // Lower sorts earlier within a slot. Ties fall back to the manifest's
    // priority, so a plugin that never sets either still lands somewhere
    // deterministic.
    public val order: Int get() = 0

    // True to take the slot over instead of adding to it — a plugin providing
    // its own scrubber rather than sitting next to the built-in one. Two
    // plugins both claiming a slot is a conflict the registry reports.
    public val replaces: Boolean get() = false
}
