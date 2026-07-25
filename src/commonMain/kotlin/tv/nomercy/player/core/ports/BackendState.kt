// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What the media engine underneath is doing. Distinct from PlayState, which is
// what the player as a whole is doing: a backend can be READY while the player
// is still IDLE because nothing asked it to start.
public enum class BackendState(public val wire: String) {
    IDLE("idle"),
    LOADING("loading"),
    READY("ready"),
    PLAYING("playing"),
    PAUSED("paused"),
    ERROR("error"),
}
