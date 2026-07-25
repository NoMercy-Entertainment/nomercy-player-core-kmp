// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Whether the queue order is shuffled.
public enum class ShuffleState(override val token: String) : TokenEnum {
    OFF("off"),
    ON("on");

    public companion object {
        public fun fromToken(token: String): ShuffleState = entries.byToken(token, "ShuffleState")
    }
}
