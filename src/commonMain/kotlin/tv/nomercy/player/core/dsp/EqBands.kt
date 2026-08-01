// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

// One band of a graphic equaliser.
//
// The frequency is where it sits, the gain is where the slider is, and the
// bandwidth is how wide it reaches. Only the gain moves in normal use — the
// other two are the layout.
public data class EqBand(
    val frequency: Int,
    val gainDb: Double,
    val bandwidth: Double = DEFAULT_BANDWIDTH,
)

// One for every band, which is what a ten-band graphic equaliser wants: narrow
// enough that adjacent sliders do different things, wide enough that a boost
// sounds like tone rather than a whistle.
public const val DEFAULT_BANDWIDTH: Double = 1.0

public object EqBands {

    // The ten frequencies the web player uses, in its order.
    //
    // Not a round decade series. They are spaced to put more resolution where
    // hearing is sharpest and music lives, which is why they cluster between
    // 600Hz and 6kHz and then jump.
    public val DEFAULT: List<EqBand> = listOf(
        EqBand(frequency = 70, gainDb = 0.0),
        EqBand(frequency = 180, gainDb = 0.0),
        EqBand(frequency = 320, gainDb = 0.0),
        EqBand(frequency = 600, gainDb = 0.0),
        EqBand(frequency = 1_000, gainDb = 0.0),
        EqBand(frequency = 3_000, gainDb = 0.0),
        EqBand(frequency = 6_000, gainDb = 0.0),
        EqBand(frequency = 12_000, gainDb = 0.0),
        EqBand(frequency = 14_000, gainDb = 0.0),
        EqBand(frequency = 16_000, gainDb = 0.0),
    )

    // What a slider bound to any of this is allowed to be.
    //
    // Straight from the web's DEFAULT_SLIDER_VALUES: the pre-gain runs -1..3 and
    // counts four steps of travel, the bands run ±12 dB and count twenty-four,
    // and both move in hundredths. A hundredth is finer than anyone can hear on
    // one nudge, which is the point — it is fine enough that a drag feels
    // continuous rather than notched.
    public val DEFAULT_SLIDER_VALUES: EqSliderValues = EqSliderValues(
        pre = SliderRange(
            min = -1.0,
            max = 3.0,
            step = 0.01,
            default = 0.0,
            totalSteps = 4.0,
        ),
        band = SliderRange(
            min = -12.0,
            max = 12.0,
            step = 0.01,
            default = 0.0,
            totalSteps = 24.0,
        ),
    )

    // The pre-gain slider, as a linear multiplier rather than decibels.
    //
    // A -1..3 slider maps to 0..4 linear, and the web does the same. It is not
    // dB and must not be treated as such: at slider zero the multiplier is one,
    // which is unity, and a dB reading of the same number would be a boost.
    //
    // Clamped at zero rather than allowed negative, because a negative
    // multiplier inverts the waveform — inaudible alone, and a comb filter the
    // moment it meets another copy of the same signal.
    public fun preGainLinear(sliderValue: Double): Double = (snapPreGain(sliderValue) + 1.0).coerceAtLeast(0.0)

    // A detent at the middle of the slider.
    //
    // Unity is the position a viewer returns to, and a pointer or a D-pad lands
    // on 0.004 rather than 0. Left alone that is a permanent, inaudible offset
    // on everything they play, and no amount of nudging removes it — the one
    // value the control cannot reach is the one it is meant to rest at.
    public fun snapPreGain(sliderValue: Double): Double =
        if (sliderValue > -SNAP_THRESHOLD && sliderValue < SNAP_THRESHOLD) 0.0 else sliderValue

    // The web's PRE_GAIN_SNAP_THRESHOLD. Wide enough to catch a fumbled
    // gesture, narrow enough that a deliberate small adjustment survives.
    private const val SNAP_THRESHOLD = 0.05
}
