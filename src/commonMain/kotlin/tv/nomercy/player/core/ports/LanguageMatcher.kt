// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Which language tags to try, best first, for one requested tag.
 *
 * A list rather than one answer, because `nl-BE` should fall back to `nl` and
 * then to whatever the player was built with. A matcher returning a single
 * string forces every caller to write that ladder again, differently — and the
 * caller that gets it wrong picks the Dutch subtitle for a Flemish viewer or no
 * subtitle at all.
 */
public fun interface LanguageMatcher {
    public fun candidatesFor(tag: String): List<String>
}
