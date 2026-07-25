// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Where the session is relative to a cast receiver.
public enum class CastState(override val token: String) : TokenEnum {
    UNAVAILABLE("unavailable"),
    AVAILABLE("available"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected");

    public companion object {
        public fun fromToken(token: String): CastState = entries.byToken(token, "CastState")
    }
}
