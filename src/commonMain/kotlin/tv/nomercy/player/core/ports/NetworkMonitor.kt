// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.Subscription

public enum class NetworkType(public val wire: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    NONE("none"),
    UNKNOWN("unknown"),
}

// What the network looked like at one moment. A snapshot, not a state: the
// player already has a NetworkState, the coarse token a chrome renders. This is
// the raw reading that one is derived from.
public data class NetworkSnapshot(val online: Boolean, val type: NetworkType)

// Read-only view of connectivity. ABR reads the type to pick a starting
// rendition, so the first seconds of a cellular session are not a 4K stall.
public interface NetworkMonitor {
    public fun isOnline(): Boolean
    public fun type(): NetworkType

    // Null when the platform will not say. A measured estimate is worth having
    // and a guessed one is not, so there is no fallback value.
    public fun downlinkMbps(): Double?
    public fun rttMs(): Double?

    public fun subscribe(fn: (NetworkSnapshot) -> Unit): Subscription
}
