// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

// The numbers a UI binds a slider to.
//
// Pure arithmetic, and worth a test for exactly that reason: nothing here fails
// loudly. A wrong ceiling draws a slider that reaches the end before the sound
// does, and a wrong percent mapping draws a handle that sits somewhere other
// than where the gain is — both look like a design decision rather than a bug.
class EqSlidersTest {

    private val values: EqSliderValues = EqBands.DEFAULT_SLIDER_VALUES

    @Test
    fun theBandSlidersAreTheWebsTwelveDecibelsEitherWay() {
        val band: SliderRange = values.sliderRangeFor(EqSliderTarget.BAND)

        assertEquals(-12.0, band.min)
        assertEquals(12.0, band.max)
        assertEquals(0.01, band.step)
        assertEquals(0.0, band.default)
        assertEquals(24.0, band.totalSteps)
    }

    // Not decibels, and this is the trap. The pre-gain slider is a linear
    // multiplier offset by one: its zero is unity, and reading -1..3 as dB
    // would make the resting position a boost.
    @Test
    fun theHeadroomSliderRunsMinusOneToThree() {
        val pre: SliderRange = values.sliderRangeFor(EqSliderTarget.PRE_GAIN)

        assertEquals(-1.0, pre.min)
        assertEquals(3.0, pre.max)
        assertEquals(0.01, pre.step)
        assertEquals(0.0, pre.default)
        assertEquals(4.0, pre.totalSteps)
    }

    @Test
    fun theEndsAndTheStepAreReadableWithoutTheRange() {
        assertEquals(-12.0, values.bandSliderMin(EqSliderTarget.BAND))
        assertEquals(12.0, values.bandSliderMax(EqSliderTarget.BAND))
        assertEquals(0.01, values.bandSliderStep(EqSliderTarget.BAND))
        assertEquals(-1.0, values.bandSliderMin(EqSliderTarget.PRE_GAIN))
        assertEquals(3.0, values.bandSliderMax(EqSliderTarget.PRE_GAIN))
    }

    @Test
    fun aFlatBandSitsInTheMiddleOfItsTrack() {
        assertEquals(0.0, values.bandSliderValue(EqSliderTarget.BAND, -12.0))
        assertEquals(50.0, values.bandSliderValue(EqSliderTarget.BAND, 0.0))
        assertEquals(100.0, values.bandSliderValue(EqSliderTarget.BAND, 12.0))
    }

    // A quarter of the way along rather than half, because the headroom slider
    // is not centred: it runs -1..3 and offsets by half of its ceiling rounded
    // down. Two mappings on purpose, and assuming one of them is how a headroom
    // handle ends up in the wrong place.
    @Test
    fun unityHeadroomSitsAQuarterOfTheWayAlong() {
        assertEquals(0.0, values.bandSliderValue(EqSliderTarget.PRE_GAIN, -1.0))
        assertEquals(25.0, values.bandSliderValue(EqSliderTarget.PRE_GAIN, 0.0))
        assertEquals(100.0, values.bandSliderValue(EqSliderTarget.PRE_GAIN, 3.0))
    }
}
