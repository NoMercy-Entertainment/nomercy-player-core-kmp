// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The reducer a visualiser draws from, measured against signals whose answer is
// known.
//
// This is the only way to test this kind of code. A spectrum reducer with its
// bands off by one still returns a frame of the right shape full of plausible
// numbers, and it draws something that looks like a visualiser — just one that
// lights the wrong bar. Feeding it a pure tone and asking which band moved is
// what catches that.
class AudioSpectrumTest {

    private fun tone(hz: Double, samples: Int = FFT_SIZE): DoubleArray =
        DoubleArray(samples) { index -> sin(TAU * hz * index / SAMPLE_RATE) }

    @Test
    fun aBassToneLightsTheBassBandAndNotTheTreble() {
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(BASS_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertTrue(frame.bandEnergies.bass > 0.3, "bass barely moved: ${frame.bandEnergies.bass}")
        assertTrue(
            frame.bandEnergies.treble < frame.bandEnergies.bass * 0.2,
            "an 80Hz tone leaked into treble: ${frame.bandEnergies}",
        )
    }

    @Test
    fun aTrebleToneLightsTheTrebleBand() {
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(TREBLE_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertTrue(
            frame.bandEnergies.treble > frame.bandEnergies.bass,
            "a 10kHz tone did not dominate treble: ${frame.bandEnergies}",
        )
    }

    @Test
    fun thePeakFrequencyIsTheToneThatWasPlayed() {
        // Within one bin's width. Asking for exactness would be asking the
        // transform for resolution it does not have — a 4096-point window at
        // 48kHz resolves to about 12Hz, and the peak lands in one of them.
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(MID_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertTrue(
            abs(frame.peakHz - MID_TONE) < frame.binHz * 1.5,
            "a ${MID_TONE}Hz tone peaked at ${frame.peakHz}Hz, bin width ${frame.binHz}",
        )
    }

    @Test
    fun everyMagnitudeIsInsideTheRangeAVisualiserExpects() {
        // A bar chart bound to these draws off the top of its box otherwise,
        // and a negative one draws downwards. Normalisation is the reducer's
        // job precisely so no consumer has to guess the scale.
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(MID_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertTrue(frame.frequency.all { it in 0.0..1.0 }, "a magnitude escaped 0..1")
        assertTrue(frame.waveform.all { it in -1.0..1.0 }, "a sample escaped -1..1")
        assertTrue(frame.energy in 0.0..1.0, "energy was ${frame.energy}")
    }

    @Test
    fun silenceIsSilentRatherThanNoisy() {
        // A reducer that divides by its own maximum turns an all-zero buffer
        // into either NaN or a full-scale display. Both are what a viewer sees
        // between tracks.
        val frame: VisualizationFrame = AudioSpectrum.analyse(DoubleArray(FFT_SIZE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertTrue(frame.frequency.all { it == 0.0 }, "silence produced a spectrum")
        assertEquals(0.0, frame.energy)
        assertEquals(0.0, frame.bandEnergies.bass)
    }

    @Test
    fun peaksHoldAndThenDecayRatherThanSnapping() {
        // The peak line on a visualiser is the part a viewer reads as loudness
        // history. Without a hold it tracks the bar exactly and shows nothing;
        // without a decay it never comes down again.
        val loud: VisualizationFrame = AudioSpectrum.analyse(tone(MID_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)
        val quiet: VisualizationFrame =
            AudioSpectrum.analyse(DoubleArray(FFT_SIZE), SAMPLE_RATE, FRAME_MS, FRAME_SECONDS, loud.peakBandEnergies)

        assertTrue(
            quiet.peakBandEnergies.mid > 0.0,
            "the peak dropped to nothing the instant the sound stopped",
        )
        assertTrue(
            quiet.peakBandEnergies.mid < loud.peakBandEnergies.mid,
            "the peak never decays: held at ${quiet.peakBandEnergies.mid}",
        )
    }

    @Test
    fun theFrameCarriesWhatItWasToldAboutTime() {
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(MID_TONE), SAMPLE_RATE, FRAME_MS, 12.5, null)

        assertEquals(12.5, frame.time)
        assertEquals(FRAME_MS, frame.deltaMs)
        assertEquals(SAMPLE_RATE, frame.sampleRate)
        assertEquals(SAMPLE_RATE.toDouble() / FFT_SIZE, frame.binHz, absoluteTolerance = 0.001)
    }

    @Test
    fun halfTheBinsAreReturnedBecauseTheOtherHalfIsAMirror() {
        // A real signal's spectrum is symmetric. Returning the whole transform
        // draws the same bars twice, once backwards.
        val frame: VisualizationFrame = AudioSpectrum.analyse(tone(MID_TONE), SAMPLE_RATE, FRAME_MS, 0.0, null)

        assertEquals(FFT_SIZE / 2, frame.frequency.size)
    }
}

private const val SAMPLE_RATE = 48_000
private const val FFT_SIZE = 4_096
private const val FRAME_MS = 16.0
private const val FRAME_SECONDS = 0.016
private const val BASS_TONE = 80.0
private const val MID_TONE = 1_000.0
private const val TREBLE_TONE = 10_000.0
private const val TAU = 2.0 * PI
