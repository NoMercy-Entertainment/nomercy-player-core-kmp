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

    // A chapter's end and the engine's duration disagree by a frame or two.
    private const val END_SLACK_MS = 1_000L

    // A seek to the end with the next source prepared needs nothing decoded at the end.
    fun endsIntoNextSource(standbyUrl: String?, loadedUrl: String?, durationMs: Long, targetMs: Long): Boolean =
        standbyUrl != null && standbyUrl != loadedUrl && durationMs > 0 && targetMs >= durationMs - END_SLACK_MS

    fun matchesSource(prerolledUrl: String?, prerolledMs: Long?, url: String?, requestedMs: Long): Boolean =
        url != null && prerolledUrl == url && matches(prerolledMs, requestedMs)
}
