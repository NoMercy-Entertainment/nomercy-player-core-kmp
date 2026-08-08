// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

// The cases are the web's `__tests__/format.test.ts`.
class ClampVolumeTest {

    @Test
    fun aValueInsideTheScaleIsRounded() {
        assertEquals(50, clampVolume(50.0))
        assertEquals(51, clampVolume(50.5))
        assertEquals(50, clampVolume(50.4))
    }

    @Test
    fun aboveTheTopIsTheTop() {
        // Not 101. A slider that can be dragged past full is a slider whose
        // readout disagrees with the sound.
        assertEquals(100, clampVolume(140.0))
    }

    @Test
    fun belowTheBottomIsSilence() {
        assertEquals(0, clampVolume(-20.0))
    }

    @Test
    fun theTopReachesFull() {
        // 99.6 shown as 99 with the slider at the top is the report that a
        // volume control "never reaches full".
        assertEquals(100, clampVolume(99.6))
    }

    @Test
    fun anUnusableReadingIsSilenceRatherThanACrash() {
        // roundToInt THROWS on NaN rather than returning anything, and an
        // exception out of a volume readout takes the chrome down with it.
        assertEquals(0, clampVolume(Double.NaN))
    }
}
