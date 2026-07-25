// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// How far through setup the player is. SETTING_UP carries the web token
// "setup", not "setting-up" — the wire string wins over the Kotlin name.
public enum class SetupState(override val token: String) : TokenEnum {
    NOT_SETUP("not-setup"),
    SETTING_UP("setup"),
    READY("ready"),
    DISPOSED("disposed");

    public companion object {
        public fun fromToken(token: String): SetupState = entries.byToken(token, "SetupState")
    }
}
