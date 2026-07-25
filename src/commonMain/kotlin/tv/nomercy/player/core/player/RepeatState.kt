// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Repeat nothing, the whole queue, or the current item.
public enum class RepeatState(override val token: String) : TokenEnum {
    OFF("off"),
    ALL("all"),
    ONE("one");

    public companion object {
        public fun fromToken(token: String): RepeatState = entries.byToken(token, "RepeatState")
    }
}
