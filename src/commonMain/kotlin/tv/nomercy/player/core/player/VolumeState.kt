// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Muted or not, independent of the volume level: unmuting restores the level
// that was set before, so the two are separate pieces of state.
public enum class VolumeState(override val token: String) : TokenEnum {
    UNMUTED("unmuted"),
    MUTED("muted");

    public companion object {
        public fun fromToken(token: String): VolumeState = entries.byToken(token, "VolumeState")
    }
}
