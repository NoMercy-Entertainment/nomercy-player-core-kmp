// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

internal object StandbySwap {
    // A caller converts the same chapter time through its own float and rounding, so the
    // seek it asks for can sit a few milliseconds from the one that was pre-rolled.
    private const val TOLERANCE_MS = 50L

    fun matches(prerolledMs: Long?, requestedMs: Long): Boolean =
        prerolledMs != null && kotlin.math.abs(prerolledMs - requestedMs) <= TOLERANCE_MS
}
