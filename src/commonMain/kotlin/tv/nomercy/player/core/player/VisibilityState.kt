// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Whether the surface the player renders into is on screen.
public enum class VisibilityState(override val token: String) : TokenEnum {
    VISIBLE("visible"),
    HIDDEN("hidden");

    public companion object {
        public fun fromToken(token: String): VisibilityState = entries.byToken(token, "VisibilityState")
    }
}
