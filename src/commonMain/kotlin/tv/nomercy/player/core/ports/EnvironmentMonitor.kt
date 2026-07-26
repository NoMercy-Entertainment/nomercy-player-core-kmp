// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.player.NetworkState
import tv.nomercy.player.core.player.VisibilityState

// What the app knows about its surroundings and the player does not.
//
// One port for two questions because they have the same shape and the same
// answer source: the host's lifecycle and connectivity observers. Splitting
// them would mean two nullable constructor arguments that are always supplied
// together.
//
// A port rather than an expect/actual, unlike the device: this changes while
// the process runs. Android answers it through ConnectivityManager and
// ProcessLifecycleOwner, iOS through NWPathMonitor and scene notifications, and
// a library that registered those itself would be a second set of observers
// beside the app's own, disagreeing about state and outliving the screen.
public interface EnvironmentMonitor {
    // Whether anything can be fetched, and roughly how well.
    //
    // SLOW is a judgement the host makes, not a number the player interprets:
    // "slow" on a metered phone connection and "slow" on a TV's ethernet are
    // different thresholds, and only the app knows which one it is on.
    public fun network(): NetworkState

    // Whether the player is on screen.
    //
    // Not whether the app is foregrounded: a player in a tab behind another, a
    // Compose screen that scrolled away, and a backgrounded app are all HIDDEN,
    // and the pause-when-hidden policy wants all three.
    public fun visibility(): VisibilityState
}
