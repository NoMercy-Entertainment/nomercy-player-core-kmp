// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlin.jvm.JvmInline

/**
 * What a player instance is called.
 *
 * A string or a number on the web, so a host numbering its players and one
 * naming them both work. Kotlin has no untagged union, so this is a value class
 * over the string form and [of] takes either — a number formatted the same way
 * the web formats it, rather than two ids that look different for one player.
 */
@JvmInline
public value class PlayerConstructorId(public val value: String) {
    public companion object {
        public fun of(id: Int): PlayerConstructorId = PlayerConstructorId(id.toString())
    }
}
