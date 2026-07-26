// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BandwidthControllerTest {

    @Test
    fun nobodyHasAnsweredYetIsZeroRatherThanAGuess() {
        // A guessed number makes adaptation decide on a measurement that never
        // happened, and it is indistinguishable from a real one afterwards.
        assertEquals(0, BandwidthController().bandwidth())
        assertNull(BandwidthController().bandwidthEstimator())
    }

    @Test
    fun theEstimatorIsAskedEveryTimeRatherThanCached() {
        // The point of an injectable estimator is that it reflects a connection
        // that moves. A cached value is a measurement from whenever the caller
        // last thought to ask.
        val subject = BandwidthController()
        var reading = 1_000_000
        subject.bandwidthEstimator { reading }

        assertEquals(1_000_000, subject.bandwidth())
        reading = 4_000_000
        assertEquals(4_000_000, subject.bandwidth())
    }

    @Test
    fun aNegativeReadingIsZeroRatherThanNegative() {
        // A host's estimator can report -1 for "unknown" the way every engine
        // does, and a negative bandwidth sorts a ladder upside down.
        val subject = BandwidthController()
        subject.bandwidthEstimator { -1 }

        assertEquals(0, subject.bandwidth())
    }

    @Test
    fun theEstimatorCanBeTakenBackAgain() {
        val subject = BandwidthController()
        subject.bandwidthEstimator { 5_000_000 }

        subject.bandwidthEstimator(null)

        assertEquals(0, subject.bandwidth())
        assertNull(subject.bandwidthEstimator())
    }
}
