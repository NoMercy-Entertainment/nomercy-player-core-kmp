// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Connectivity as the player sees it. SLOW is a measured judgement, not a
// platform flag: the platform only reports online and offline.
public enum class NetworkState(override val token: String) : TokenEnum {
    ONLINE("online"),
    OFFLINE("offline"),
    SLOW("slow");

    public companion object {
        public fun fromToken(token: String): NetworkState = entries.byToken(token, "NetworkState")
    }
}
