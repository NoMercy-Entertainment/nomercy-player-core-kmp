// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// What playback itself is doing. A chrome binds to this; the phase is for the
// host that owns the player's life.
public enum class PlayState(override val token: String) : TokenEnum {
    IDLE("idle"),
    LOADING("loading"),
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
    ERROR("error");

    public companion object {
        public fun fromToken(token: String): PlayState = entries.byToken(token, "PlayState")
    }
}
