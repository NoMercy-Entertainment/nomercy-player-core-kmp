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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000

// The ten bands a graphic equaliser offers.
private val BANDS = intArrayOf(70, 180, 320, 600, 1_000, 3_000, 6_000, 12_000, 14_000, 16_000)

// A peaking filter has one property that cannot be faked: its gain at its own
// centre frequency is exactly what was asked for. Everything else about an EQ
// can look right while being wrong; this identity cannot.
class BiquadPeakingTest {

    private fun decibels(linear: Double): Double = 20.0 * log10(linear)

    private fun band(frequencyHz: Double, gainDb: Double, bandwidth: Double = 1.0): BiquadCoefficients =
        BiquadPeaking.coefficients(frequencyHz, gainDb, bandwidth, SAMPLE_RATE)

    @Test
    fun everyBandGivesExactlyTheGainItWasAskedFor() {
        for (frequency in BANDS) {
            for (gainDb in doubleArrayOf(-12.0, -6.0, 6.0, 12.0)) {
                val measured: Double = decibels(
                    BiquadPeaking.magnitudeAt(band(frequency.toDouble(), gainDb), frequency.toDouble(), SAMPLE_RATE),
                )

                assertTrue(
                    abs(measured - gainDb) < 0.2,
                    "band ${frequency}Hz asked for $gainDb dB and gave $measured dB",
                )
            }
        }
    }

    @Test
    fun aBoostAndAnEqualCutAreExactMirrors() {
        // The cookbook takes the square root of the linear gain for exactly
        // this. Get it wrong and +6 followed by -6 does not return the signal to
        // where it started, which is what a viewer does every time they undo a
        // slider.
        val up: Double = decibels(BiquadPeaking.magnitudeAt(band(1_000.0, 6.0), 1_000.0, SAMPLE_RATE))
        val down: Double = decibels(BiquadPeaking.magnitudeAt(band(1_000.0, -6.0), 1_000.0, SAMPLE_RATE))

        assertTrue(abs(up + down) < 0.01, "a +6dB boost and a -6dB cut did not cancel: $up and $down")
    }

    @Test
    fun aFlatBandChangesNothing() {
        // The position every slider sits at until someone moves it. A filter
        // that coloured the sound at zero would colour it for everyone.
        for (frequency in BANDS) {
            val measured: Double = decibels(
                BiquadPeaking.magnitudeAt(band(frequency.toDouble(), 0.0), frequency.toDouble(), SAMPLE_RATE),
            )

            assertTrue(abs(measured) < 0.001, "a flat band at ${frequency}Hz changed the level by $measured dB")
        }
    }

    @Test
    fun aBandLeavesDistantFrequenciesAlone() {
        // The point of a band. One that lifted everything would be a volume
        // control with extra steps.
        val bass: BiquadCoefficients = band(70.0, 12.0)

        val atTreble: Double = decibels(BiquadPeaking.magnitudeAt(bass, 10_000.0, SAMPLE_RATE))

        assertTrue(abs(atTreble) < 0.5, "a 70Hz boost changed 10kHz by $atTreble dB")
    }

    @Test
    fun aNarrowerBandIsNarrower() {
        // What Q means. Without this the parameter could be ignored entirely and
        // every test above would still pass.
        val wide: BiquadCoefficients = band(1_000.0, 12.0, bandwidth = 0.5)
        val narrow: BiquadCoefficients = band(1_000.0, 12.0, bandwidth = 4.0)

        val wideAtNeighbour: Double = decibels(BiquadPeaking.magnitudeAt(wide, 2_000.0, SAMPLE_RATE))
        val narrowAtNeighbour: Double = decibels(BiquadPeaking.magnitudeAt(narrow, 2_000.0, SAMPLE_RATE))

        assertTrue(
            wideAtNeighbour > narrowAtNeighbour,
            "a wide band spilled less into its neighbour than a narrow one",
        )
    }

    @Test
    fun filteringATonePassesItThroughAtTheAskedGain() {
        // The transfer function is arithmetic; this is the filter actually
        // running over samples. Both have to agree, or the maths describes a
        // filter the audio path does not apply.
        val boost: BiquadCoefficients = band(1_000.0, 12.0)
        val tone = DoubleArray(SAMPLE_RATE) { index -> sin(2.0 * PI * 1_000.0 * index / SAMPLE_RATE) }

        val filtered: DoubleArray = BiquadPeaking.process(boost, tone)

        // The tail, after the filter has settled — the first samples are the
        // transient every recursive filter starts with.
        val peakIn: Double = tone.drop(SAMPLE_RATE / 2).maxOf { abs(it) }
        val peakOut: Double = filtered.drop(SAMPLE_RATE / 2).maxOf { abs(it) }

        assertTrue(
            abs(decibels(peakOut / peakIn) - 12.0) < 0.5,
            "a 12dB boost moved a 1kHz tone by ${decibels(peakOut / peakIn)} dB",
        )
    }

    @Test
    fun aFlatFilterReturnsTheSamplesItWasGiven() {
        val tone = DoubleArray(1_000) { index -> sin(2.0 * PI * 440.0 * index / SAMPLE_RATE) }

        val filtered: DoubleArray = BiquadPeaking.process(band(1_000.0, 0.0), tone)

        assertTrue(tone.indices.all { abs(filtered[it] - tone[it]) < 1e-9 }, "a flat filter changed the samples")
    }

    @Test
    fun stateCarriesAcrossBlocksSoAStreamDoesNotClick() {
        // A player filters a stream block by block. A filter that restarted at
        // every boundary would put a discontinuity there, and a discontinuity in
        // audio is a click.
        val boost: BiquadCoefficients = band(1_000.0, 12.0)
        val tone = DoubleArray(2_000) { index -> sin(2.0 * PI * 1_000.0 * index / SAMPLE_RATE) }

        val whole: DoubleArray = BiquadPeaking.process(boost, tone)

        val state = BiquadState()
        val blocked = DoubleArray(tone.size) { index -> state.step(boost, tone[index]) }

        assertTrue(
            tone.indices.all { abs(whole[it] - blocked[it]) < 1e-9 },
            "filtering in blocks did not match filtering the whole buffer",
        )
    }

    @Test
    fun resettingClearsTheHistoryASeekInvalidated() {
        // After a seek the history describes audio the viewer has left, and
        // feeding it forward smears the old passage into the new one.
        val boost: BiquadCoefficients = band(1_000.0, 12.0)
        val state = BiquadState()
        repeat(100) { state.step(boost, 1.0) }

        state.reset()

        assertTrue(abs(state.step(boost, 0.0)) < 1e-9, "history survived a reset")
    }
}
