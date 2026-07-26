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
import kotlin.math.sin

// The analysis window.
//
// A spectrum is taken from a slice of audio, and a slice has edges the signal
// does not. Cutting a tone mid-cycle looks to the transform like a step, and a
// step has energy at every frequency — which draws as a spectrum smeared across
// the whole display instead of one bar.
//
// Hann because it is the one every audio analyser reaches for: enough
// suppression of that smear to see a real peak, and a wide enough main lobe that
// a peak stays one bar rather than splitting.
public object HannWindow {

    public fun apply(samples: DoubleArray): DoubleArray {
        if (samples.size <= 1) return samples.copyOf()

        val last: Double = (samples.size - 1).toDouble()
        return DoubleArray(samples.size) { index ->
            val shape: Double = HALF * (1.0 - cos(TURN * index / last))
            samples[index] * shape
        }
    }

    private const val HALF = 0.5

    private const val TURN = 2.0 * PI
}

// The frequency content of a block of samples.
//
// Radix-2 Cooley–Tukey, in pure Kotlin. No java.* anywhere, because a spectrum
// analyser that only worked on Android would mean the desktop and iOS visualisers
// each grew their own — three implementations of one piece of arithmetic, and the
// first time one was tuned they would stop agreeing.
public object Fft {

    // The magnitude of each of the first N/2 bins.
    //
    // Half, because a real signal's spectrum is symmetric: the upper half is the
    // mirror of the lower and returning it would draw the same bars twice, once
    // backwards.
    public fun magnitudes(samples: DoubleArray): DoubleArray {
        require(samples.isNotEmpty() && samples.size and (samples.size - 1) == 0) {
            "FFT size must be a power of two, got ${samples.size}"
        }

        val real: DoubleArray = samples.copyOf()
        val imaginary = DoubleArray(samples.size)
        transform(real, imaginary)

        return DoubleArray(samples.size / RADIX) { bin -> hypot(real[bin], imaginary[bin]) }
    }

    // In place, because the alternative at this size is an allocation per frame
    // and this runs sixty times a second behind a visualiser.
    private fun transform(real: DoubleArray, imaginary: DoubleArray) {
        val size: Int = real.size
        reverseBits(real, imaginary)

        var span = RADIX
        while (span <= size) {
            var start = 0
            while (start < size) {
                butterfly(real, imaginary, start, span)
                start += span
            }
            span = span shl 1
        }
    }

    // One pass over a span: combine each pair separated by half the span,
    // rotated by its position. This is the whole algorithm; everything around it
    // is bookkeeping about which pairs to hand it.
    private fun butterfly(real: DoubleArray, imaginary: DoubleArray, start: Int, span: Int) {
        val step: Double = -FULL_TURN / span
        val half: Int = span / RADIX

        for (offset in 0 until half) {
            val angle: Double = step * offset
            val cosine: Double = cos(angle)
            val sine: Double = sin(angle)

            val here: Int = start + offset
            val there: Int = here + half

            val realPart: Double = real[there] * cosine - imaginary[there] * sine
            val imaginaryPart: Double = real[there] * sine + imaginary[there] * cosine

            real[there] = real[here] - realPart
            imaginary[there] = imaginary[here] - imaginaryPart
            real[here] += realPart
            imaginary[here] += imaginaryPart
        }
    }

    // The reordering the algorithm needs before it can work in place: a sample
    // at index i belongs at the index whose bits are i's reversed.
    private fun reverseBits(real: DoubleArray, imaginary: DoubleArray) {
        val size: Int = real.size
        var target = 0

        for (index in 0 until size - 1) {
            if (index < target) {
                swap(real, index, target)
                swap(imaginary, index, target)
            }
            var mask: Int = size shr 1
            while (target and mask != 0) {
                target = target and mask.inv()
                mask = mask shr 1
            }
            target = target or mask
        }
    }

    // Two, because this is a radix-2 transform: it halves the problem at each
    // step, which is why the size has to be a power of two.
    private const val RADIX = 2

    // Two pi. A full rotation, which is what one span covers.
    private const val FULL_TURN = 2.0 * PI

    private fun swap(values: DoubleArray, first: Int, second: Int) {
        val held: Double = values[first]
        values[first] = values[second]
        values[second] = held
    }
}
