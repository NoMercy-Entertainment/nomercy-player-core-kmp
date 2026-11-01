// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresentedFrameClockTest {

    private val ms = 1_000_000L

    @Test
    fun noFrameYetIsNoAnswer() {
        assertNull(PresentedFrameClock().timeAt(nowNs = 5 * ms, rate = 1.0, playing = true))
    }

    @Test
    fun theFrameBeingShownAnswersWithItsOwnPresentationTime() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 100 * ms, rate = 1.0, playing = true))
    }

    @Test
    fun timeAdvancesFromTheLastFrameNotFromAPollEveryQuarterSecond() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.033, clock.timeAt(nowNs = 133 * ms, rate = 1.0, playing = true)!!, 1e-9)
    }

    @Test
    fun aFrameAnnouncedAheadOfItsReleaseDoesNotRunTheClockForward() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 120 * ms)

        assertEquals(9.98, clock.timeAt(nowNs = 100 * ms, rate = 1.0, playing = true)!!, 1e-9)
    }

    @Test
    fun theRateScalesTheElapsedWallTime() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.2, clock.timeAt(nowNs = 200 * ms, rate = 2.0, playing = true)!!, 1e-9)
    }

    @Test
    fun pausedHoldsTheLastShownFrame() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 5_000 * ms, rate = 1.0, playing = false))
    }

    @Test
    fun aClockThatStoppedReceivingFramesParksInsteadOfRunningAway() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertNull(clock.timeAt(nowNs = 100 * ms + PresentedFrameClock.MAX_CARRY_NS + 1, rate = 1.0, playing = true))
    }

    @Test
    fun aSeekDropsTheAnchorUntilTheNextFrameArrives() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)
        clock.reset()

        assertNull(clock.timeAt(nowNs = 110 * ms, rate = 1.0, playing = true))
    }
}
