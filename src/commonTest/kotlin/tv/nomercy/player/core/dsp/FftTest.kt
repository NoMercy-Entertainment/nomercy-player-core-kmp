// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000
private const val SIZE = 4_096

// A spectrum measured against a signal whose answer is known.
//
// The whole risk in an FFT is that it produces plausible-looking numbers while
// being wrong: a bit-reversal off by one still returns an array of the right
// shape full of energy. Feeding it a pure tone and checking the peak lands in
// that tone's own bin is the only assertion that catches it.
class FftTest {

    private fun tone(frequencyHz: Double, size: Int = SIZE): DoubleArray =
        DoubleArray(size) { index -> sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE) }

    private fun peakBin(magnitudes: DoubleArray): Int =
        magnitudes.indices.maxByOrNull { magnitudes[it] } ?: 0

    private fun binFor(frequencyHz: Double, size: Int = SIZE): Int =
        (frequencyHz / (SAMPLE_RATE.toDouble() / size)).toInt()

    @Test
    fun aPureTonePeaksInItsOwnBin() {
        val magnitudes: DoubleArray = Fft.magnitudes(HannWindow.apply(tone(1_000.0)))

        assertTrue(
            abs(peakBin(magnitudes) - binFor(1_000.0)) <= 1,
            "a 1kHz tone peaked in bin ${peakBin(magnitudes)}, expected about ${binFor(1_000.0)}",
        )
    }

    @Test
    fun aHigherTonePeaksHigher() {
        // Two tones an octave apart must land two different places, or the
        // transform is returning something that only looks like a spectrum.
        val low: Int = peakBin(Fft.magnitudes(HannWindow.apply(tone(500.0))))
        val high: Int = peakBin(Fft.magnitudes(HannWindow.apply(tone(4_000.0))))

        assertTrue(high > low * 2, "500Hz peaked at $low and 4kHz at $high")
    }

    @Test
    fun theSpectrumIsHalfTheInputLength() {
        // A real signal's spectrum is symmetric. Returning the upper half draws
        // the same bars twice, once backwards.
        assertEquals(SIZE / 2, Fft.magnitudes(tone(1_000.0)).size)
    }

    @Test
    fun silenceHasNoEnergyAnywhere() {
        val magnitudes: DoubleArray = Fft.magnitudes(DoubleArray(SIZE))

        assertTrue(magnitudes.all { it < 1e-9 }, "silence produced energy")
    }

    @Test
    fun aSizeThatIsNotAPowerOfTwoIsRefusedRatherThanTruncated() {
        // Radix-2 needs one. Quietly truncating the input analyses a different
        // slice of audio than the caller handed over, and the spectrum is wrong
        // in a way nothing reports.
        assertFailsWith<IllegalArgumentException> { Fft.magnitudes(DoubleArray(1_000)) }
    }

    @Test
    fun anEmptyInputIsRefused() {
        assertFailsWith<IllegalArgumentException> { Fft.magnitudes(DoubleArray(0)) }
    }

    @Test
    fun aSmallPowerOfTwoStillWorks() {
        // A visualiser on a weak device drops its window size. The algorithm has
        // to hold at 64 as well as at 4096.
        val magnitudes: DoubleArray = Fft.magnitudes(HannWindow.apply(tone(6_000.0, size = 64)))

        assertEquals(32, magnitudes.size)
        assertTrue(abs(peakBin(magnitudes) - binFor(6_000.0, size = 64)) <= 1)
    }

    @Test
    fun theWindowTapersToNothingAtBothEnds() {
        // The taper is the whole point: a slice cut mid-cycle looks like a step
        // to the transform, and a step has energy at every frequency — which
        // draws as a spectrum smeared across the display instead of one bar.
        val windowed: DoubleArray = HannWindow.apply(DoubleArray(64) { 1.0 })

        assertTrue(windowed.first() < 1e-9)
        assertTrue(windowed.last() < 1e-9)
        assertTrue(windowed[32] > 0.9, "the window suppressed the middle of the block")
    }

    @Test
    fun theWindowLeavesTheInputAlone() {
        // The caller's buffer is usually the engine's, reused every frame.
        val samples = DoubleArray(64) { 1.0 }

        HannWindow.apply(samples)

        assertTrue(samples.all { it == 1.0 }, "the window wrote into the caller's buffer")
    }

    @Test
    fun windowingSharpensThePeakRatherThanMovingIt() {
        // A window that shifted the peak would trade one wrong answer for
        // another.
        val raw: DoubleArray = Fft.magnitudes(tone(1_000.0))
        val windowed: DoubleArray = Fft.magnitudes(HannWindow.apply(tone(1_000.0)))

        assertTrue(abs(peakBin(raw) - peakBin(windowed)) <= 1)
    }

    @Test
    fun aSingleSampleWindowIsNotADivisionByZero() {
        // The taper divides by size minus one, which is zero here. A visualiser
        // starting up with a one-sample buffer should not take the player down.
        assertEquals(1, HannWindow.apply(DoubleArray(1) { 1.0 }).size)
    }
}
