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

    // A next-episode load that swaps in a warm engine carries the old engine's play intent, so
    // the new renderers start in the swap itself and not after the load's callers have run.
    fun playsOnLoadSwap(previousPlayWhenReady: Boolean, startsFresh: Boolean): Boolean =
        startsFresh && previousPlayWhenReady

    // The new engine plays and is on screen before the old one stops.
    inline fun handOver(startNew: () -> Unit, showNew: () -> Unit, stopOld: () -> Unit) {
        startNew()
        showNew()
        stopOld()
    }

    fun matchesSource(prerolledUrl: String?, prerolledMs: Long?, url: String?, requestedMs: Long): Boolean =
        url != null && prerolledUrl == url && matches(prerolledMs, requestedMs)
}
