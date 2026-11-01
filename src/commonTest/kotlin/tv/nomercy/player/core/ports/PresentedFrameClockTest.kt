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
        assertNull(PresentedFrameClock().timeAt(nowNs = 5 * ms))
    }

    @Test
    fun theFrameBeingShownAnswersWithItsOwnPresentationTime() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 100 * ms))
    }

    @Test
    fun betweenTwoFramesTheShownFrameKeepsItsOwnPresentationTime() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 133 * ms))
    }

    @Test
    fun aFrameAnnouncedAheadOfItsReleaseIsNotShownYet() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)
        clock.onFrame(ptsUs = 10_041_667L, releaseNs = 141 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 120 * ms))
        assertEquals(10.041667, clock.timeAt(nowNs = 142 * ms))
    }

    @Test
    fun severalFramesAnnouncedAheadStillAnswerWithTheOneOnScreen() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)
        clock.onFrame(ptsUs = 10_041_667L, releaseNs = 141 * ms)
        clock.onFrame(ptsUs = 10_083_333L, releaseNs = 183 * ms)

        assertEquals(10.041667, clock.timeAt(nowNs = 150 * ms))
    }

    @Test
    fun aPausedVideoHoldsTheLastShownFrame() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)

        assertEquals(10.0, clock.timeAt(nowNs = 5_000 * ms))
    }

    @Test
    fun aSeekDropsTheFramesUntilTheNextOneArrives() {
        val clock = PresentedFrameClock()
        clock.onFrame(ptsUs = 10_000_000L, releaseNs = 100 * ms)
        clock.reset()

        assertNull(clock.timeAt(nowNs = 110 * ms))
        clock.onFrame(ptsUs = 60_000_000L, releaseNs = 120 * ms)
        assertEquals(60.0, clock.timeAt(nowNs = 121 * ms))
    }
}
