// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Whether the rendition is chosen by ABR or pinned by the viewer.
public enum class QualityState(override val token: String) : TokenEnum {
    AUTO("auto"),
    MANUAL("manual");

    public companion object {
        public fun fromToken(token: String): QualityState = entries.byToken(token, "QualityState")
    }
}
