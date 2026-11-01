// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The presentation time of the picture on screen, from the decoder's frame reports.
// A report arrives before its release, so the answer is the newest frame already
// released, and it stays that frame's own time until the next one is released.
internal class PresentedFrameClock {

    @kotlin.concurrent.Volatile private var recent: List<Anchor> = emptyList()

    fun onFrame(ptsUs: Long, releaseNs: Long) {
        recent = (recent + Anchor(ptsUs, releaseNs)).takeLast(KEPT)
    }

    fun reset() {
        recent = emptyList()
    }

    // Null when no frame has been reported; the caller falls back to its own clock.
    fun timeAt(nowNs: Long): Double? {
        val held: List<Anchor> = recent
        val shown: Anchor = held.lastOrNull { it.releaseNs <= nowNs } ?: held.firstOrNull() ?: return null
        return shown.ptsUs / US_PER_SECOND
    }

    private class Anchor(val ptsUs: Long, val releaseNs: Long)

    private companion object {
        // Frames the decoder announces ahead of their release.
        const val KEPT = 8
        const val US_PER_SECOND = 1_000_000.0
    }
}
