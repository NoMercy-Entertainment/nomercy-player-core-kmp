// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Whether the audio track is the stream's default or a viewer's choice.
public enum class AudioTrackState(override val token: String) : TokenEnum {
    DEFAULT("default"),
    MANUAL("manual");

    public companion object {
        public fun fromToken(token: String): AudioTrackState = entries.byToken(token, "AudioTrackState")
    }
}
