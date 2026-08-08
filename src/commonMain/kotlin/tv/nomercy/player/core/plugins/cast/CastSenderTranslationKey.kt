// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * Every string the cast sender shows a viewer.
 *
 * An enum rather than loose keys, because a missing translation on this surface
 * is a button labelled `cast.connecting` on somebody's television. The compiler
 * catches that here; a map lookup catches it in the living room.
 */
public enum class CastSenderTranslationKey(public val key: String) {
    CAST_TO("cast.castTo"),
    CONNECTING("cast.connecting"),
    CONNECTED_TO("cast.connectedTo"),
    DISCONNECT("cast.disconnect"),
    CASTING("cast.casting"),
    UNAVAILABLE("cast.unavailable"),
}
