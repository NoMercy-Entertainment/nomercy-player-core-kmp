// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The media time of the picture on screen, from the decoder's own frame reports.
//
// An engine that is polled for its position answers with a value that was true
// a quarter of a second ago. Anything drawn over the picture and meant to move
// with it (styled subtitles) needs the time of the frame that is shown, which is
// what the web gets from requestVideoFrameCallback. Each report here is one
// frame's presentation time and the wall-clock instant it is released at; the
// time at any other instant is that frame plus the elapsed wall time.
internal class PresentedFrameClock {

    @kotlin.concurrent.Volatile private var anchor: Anchor? = null

    fun onFrame(ptsUs: Long, releaseNs: Long) {
        anchor = Anchor(ptsUs, releaseNs)
    }

    fun reset() {
        anchor = null
    }

    // Null when no frame has been reported, or the reports stopped long enough
    // ago that extrapolating would be a guess; the caller falls back to its own
    // clock then.
    fun timeAt(nowNs: Long, rate: Double, playing: Boolean): Double? {
        val held: Anchor = anchor ?: return null
        if (!playing) return held.ptsUs / US_PER_SECOND
        val elapsedNs: Long = nowNs - held.releaseNs
        if (elapsedNs > MAX_CARRY_NS) return null
        return held.ptsUs / US_PER_SECOND + elapsedNs * rate / NS_PER_SECOND
    }

    private class Anchor(val ptsUs: Long, val releaseNs: Long)

    internal companion object {
        // Two frames at 24fps: past this the decoder has stopped (buffering, end).
        const val MAX_CARRY_NS: Long = 100_000_000L
        private const val US_PER_SECOND = 1_000_000.0
        private const val NS_PER_SECOND = 1_000_000_000.0
    }
}
