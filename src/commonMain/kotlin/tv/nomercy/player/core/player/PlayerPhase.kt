// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Where the player is in its own lifecycle. Broader than PlayState: it covers
// setup and disposal, which have no play/pause meaning.
public enum class PlayerPhase(override val token: String) : TokenEnum {
    IDLE("idle"),
    SETUP("setup"),
    READY("ready"),
    LOADING("loading"),
    STARTING("starting"),
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    SEEKING("seeking"),
    ENDED("ended"),
    STOPPED("stopped"),
    DISPOSING("disposing"),
    DISPOSED("disposed");

    public companion object {
        public fun fromToken(token: String): PlayerPhase = entries.byToken(token, "PlayerPhase")
    }
}
