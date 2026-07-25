// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Why the pipeline is waiting on data, when it is.
public enum class BufferState(override val token: String) : TokenEnum {
    IDLE("idle"),
    LOADING("loading"),
    SEEKING("seeking"),
    STALLED("stalled");

    public companion object {
        public fun fromToken(token: String): BufferState = entries.byToken(token, "BufferState")
    }
}
