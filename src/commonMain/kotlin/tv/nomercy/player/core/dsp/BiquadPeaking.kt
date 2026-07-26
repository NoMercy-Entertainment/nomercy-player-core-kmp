// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

// One filter section, unnormalised.
//
// a0 is kept rather than divided out at construction because the transfer
// function and the difference equation both want it, and normalising in two
// places is two chances to normalise differently.
public data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
)

// A peaking equaliser band.
//
// The same filter a browser's BiquadFilterNode(type='peaking') applies, from the
// same cookbook, so a viewer who set up an equaliser on the web hears the same
// thing on a phone. That is not a nicety: an EQ that sounded different per
// platform would be a setting people had to redo per device.
public object BiquadPeaking {

    // The RBJ Audio-EQ-Cookbook peaking filter.
    //
    // [bandwidth] is the filter's Q: how wide the band is, inversely. One is a
    // reasonable default for a ten-band graphic equaliser — narrow enough that
    // adjacent bands are distinguishable, wide enough that a boost sounds like
    // tone rather than a whistle. Higher is narrower.
    public fun coefficients(
        frequencyHz: Double,
        gainDb: Double,
        bandwidth: Double,
        sampleRate: Int,
    ): BiquadCoefficients {
        // Amplitude, and the square root of the gain rather than the gain
        // itself. The cookbook defines A this way for peaking and shelving
        // filters so the boost and the cut of the same magnitude are exact
        // mirrors — get this wrong and a +6 followed by a -6 does not return
        // the signal to where it started.
        val amplitude: Double = TEN.pow(gainDb / GAIN_DIVISOR)
        val omega: Double = 2.0 * PI * frequencyHz / sampleRate
        val sine: Double = sin(omega)
        val cosine: Double = cos(omega)
        val alpha: Double = sine / (2.0 * bandwidth)

        return BiquadCoefficients(
            b0 = 1.0 + alpha * amplitude,
            b1 = -2.0 * cosine,
            b2 = 1.0 - alpha * amplitude,
            a0 = 1.0 + alpha / amplitude,
            a1 = -2.0 * cosine,
            a2 = 1.0 - alpha / amplitude,
        )
    }

    // How much this filter changes a tone at [frequencyHz], as a ratio.
    //
    // Evaluated from the coefficients rather than measured by running a tone
    // through, which means a test can check the filter is what it claims without
    // an engine, a buffer, or a sample rate's worth of arithmetic per assertion.
    public fun magnitudeAt(coefficients: BiquadCoefficients, frequencyHz: Double, sampleRate: Int): Double {
        val omega: Double = 2.0 * PI * frequencyHz / sampleRate
        val cos1: Double = cos(omega)
        val sin1: Double = sin(omega)
        val cos2: Double = cos(2.0 * omega)
        val sin2: Double = sin(2.0 * omega)

        val numerator: Double = hypot(
            coefficients.b0 + coefficients.b1 * cos1 + coefficients.b2 * cos2,
            -(coefficients.b1 * sin1 + coefficients.b2 * sin2),
        )
        val denominator: Double = hypot(
            coefficients.a0 + coefficients.a1 * cos1 + coefficients.a2 * cos2,
            -(coefficients.a1 * sin1 + coefficients.a2 * sin2),
        )

        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    // The filter, actually applied.
    //
    // Direct Form I, which keeps the input and output histories separate. Direct
    // Form II uses less memory and is the wrong trade here: it accumulates
    // rounding differently and at the gains a graphic equaliser reaches, that is
    // audible on quiet passages.
    //
    // State is per call. A caller filtering a stream block by block wants
    // [BiquadState] instead, or the filter restarts at every block boundary and
    // clicks.
    public fun process(coefficients: BiquadCoefficients, input: DoubleArray): DoubleArray {
        val state = BiquadState()
        return DoubleArray(input.size) { index -> state.step(coefficients, input[index]) }
    }

    private const val TEN = 10.0

    // Forty, not twenty. The cookbook's A is the square root of the linear gain,
    // and using twenty here makes every band twice the requested strength.
    private const val GAIN_DIVISOR = 40.0
}

// The history one filter section carries between blocks.
//
// Separate from the coefficients because the coefficients are a setting and this
// is a position: changing the gain must not reset where the filter is, or every
// slider move clicks.
public class BiquadState {

    private var lastInput: Double = 0.0
    private var priorInput: Double = 0.0
    private var lastOutput: Double = 0.0
    private var priorOutput: Double = 0.0

    public fun step(coefficients: BiquadCoefficients, sample: Double): Double {
        val output: Double = (
            coefficients.b0 * sample +
                coefficients.b1 * lastInput +
                coefficients.b2 * priorInput -
                coefficients.a1 * lastOutput -
                coefficients.a2 * priorOutput
            ) / coefficients.a0

        priorInput = lastInput
        lastInput = sample
        priorOutput = lastOutput
        lastOutput = output

        return output
    }

    // For a seek, where the history describes audio the viewer has left.
    public fun reset() {
        lastInput = 0.0
        priorInput = 0.0
        lastOutput = 0.0
        priorOutput = 0.0
    }
}
