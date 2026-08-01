// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.floor

// Which of the two ranges a control is asking about.
//
// The web spells this as the string 'Pre' sitting in the frequency field of a
// pseudo-band, because its band list has to survive a JSON round trip. Nothing
// here needs that: the pre-gain is its own control on the plugin, so the
// distinction is a type rather than a sentinel frequency nobody can look up.
public enum class EqSliderTarget {
    PRE_GAIN,
    BAND,
}

// The ends of a slider, the size of one nudge, and where it rests.
//
// A television D-pad needs these as much as an `input[type=range]` does — more,
// because a remote has no drag and every press has to land on a step. Hard-coding
// them in a UI is how two screens in the same app end up disagreeing about how
// far a boost goes.
public data class SliderRange(
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
    // The full travel, which is not always max - min: the pre-gain slider runs
    // -1..3 and counts four. It is the divisor the percent mapping uses, so it
    // is carried rather than derived.
    val totalSteps: Double,
)

// Both ranges, and the arithmetic every consumer would otherwise write again.
public data class EqSliderValues(
    val pre: SliderRange,
    val band: SliderRange,
) {

    public fun sliderRangeFor(target: EqSliderTarget): SliderRange =
        if (target == EqSliderTarget.PRE_GAIN) pre else band

    public fun bandSliderMin(target: EqSliderTarget): Double = sliderRangeFor(target).min

    public fun bandSliderMax(target: EqSliderTarget): Double = sliderRangeFor(target).max

    public fun bandSliderStep(target: EqSliderTarget): Double = sliderRangeFor(target).step

    // A gain as the 0-100 a progress track binds to.
    //
    // The offset is the web's, including the floor: a band slider is centred, so
    // it shifts by the whole of max and zero lands at fifty; the pre-gain slider
    // is not, so it shifts by half of max rounded down and zero lands at
    // twenty-five. Two different mappings on purpose, because the two sliders
    // do not have the same shape.
    public fun bandSliderValue(target: EqSliderTarget, gain: Double): Double {
        val range: SliderRange = sliderRangeFor(target)
        val offset: Double =
            if (target == EqSliderTarget.PRE_GAIN) floor(range.max / HALF) else range.max

        return ((gain + offset) / range.totalSteps) * PERCENT
    }
}

private const val HALF = 2.0
private const val PERCENT = 100.0
