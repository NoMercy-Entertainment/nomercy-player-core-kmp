// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Whether the display satisfies a media query.
 *
 * Narrow on purpose. The quality ladder asks about dynamic range and colour
 * gamut and nothing else; handing it the whole screen API would let the ladder
 * come to depend on a dozen more things, none of which a test can reproduce.
 */
public fun interface DisplayRangeProbe {
    public fun matches(query: String): Boolean
}
