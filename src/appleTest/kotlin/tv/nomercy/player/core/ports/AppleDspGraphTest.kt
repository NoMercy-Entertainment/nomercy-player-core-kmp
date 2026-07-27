// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.core.dsp.Fft
import tv.nomercy.player.core.dsp.HannWindow
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The Apple equaliser, measured on the simulator in decibels.
//
// The filter is shared with the desktop and Android, so what this proves is
// that Apple reaches the same numbers — not that a biquad works, which is
// settled elsewhere. A platform that quietly used a different curve would sound
// different with the same settings, and nobody could reproduce it without both
// devices side by side.
//
// The tap plumbing itself is not exercised here: attaching one needs a playing
// AVPlayerItem with an audio track, which the simulator's missing audio route
// makes unreliable in the same way it makes time progression unreliable. That
// is device QA, and it is named rather than pretended at.
class AppleDspGraphTest {

    private val graph = AppleDspGraph(SAMPLE_RATE, CHANNELS)

    @AfterTest
    fun release() {
        graph.release()
    }

    private fun tone(hz: Double): FloatArray {
        val samples = FloatArray(FFT_SIZE * CHANNELS)
        for (frame in 0 until FFT_SIZE) {
            val value: Float = (sin(2.0 * PI * hz * frame / SAMPLE_RATE) * AMPLITUDE).toFloat()
            for (channel in 0 until CHANNELS) samples[frame * CHANNELS + channel] = value
        }
        return samples
    }

    private fun levelDb(samples: FloatArray, hz: Double): Double {
        val mono = DoubleArray(FFT_SIZE) { frame -> samples[frame * CHANNELS].toDouble() }
        val magnitudes: DoubleArray = Fft.magnitudes(HannWindow.apply(mono))
        val bin: Int = (hz * FFT_SIZE / SAMPLE_RATE).toInt()
        return 20.0 * log10((bin - 1..bin + 1).maxOf { magnitudes[it] })
    }

    // The same entry point the tap calls: shape() is a pointer copy around
    // shapeSamples(). A gate that reimplemented the filtering to avoid the
    // pointer would measure its own arithmetic rather than the graph's.
    private fun shaped(hz: Double): Double {
        val samples: FloatArray = tone(hz)
        val before: Double = levelDb(tone(hz), hz)
        graph.shapeSamples(samples, FFT_SIZE)
        return levelDb(samples, hz) - before
    }

    @Test
    fun boostingABandRaisesItByTheNumberItWasGiven() {
        graph.bandGain(TONE.toInt(), BOOST_DB)

        val measured: Double = shaped(TONE)

        assertTrue(abs(measured - BOOST_DB) < TOLERANCE_DB, "asked for ${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun cuttingABandLowersItByTheNumberItWasGiven() {
        graph.bandGain(TONE.toInt(), -BOOST_DB)

        val measured: Double = shaped(TONE)

        assertTrue(abs(measured + BOOST_DB) < TOLERANCE_DB, "asked for -${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun aFlatChainIsTransparent() {
        assertTrue(abs(shaped(TONE)) < LEAK_DB, "a flat chain coloured the sound")
    }

    @Test
    fun aBandLeavesTheRestOfTheSpectrumAlone() {
        graph.bandGain(TONE.toInt(), BOOST_DB)

        assertTrue(abs(shaped(FAR_TONE)) < LEAK_DB, "a 1kHz boost moved 10kHz")
    }

    @Test
    fun aPresetReachesTheFilterAsAWhole() {
        // Against the combined response of all ten bands, not against one band's
        // gain — and that correction is the point of the test.
        //
        // It first asserted that the level at a band's centre equals that band's
        // number, and Rock failed it: 6.75dB asked for at 12kHz, 16.1dB
        // measured. Nothing was wrong. Rock boosts 12k, 14k and 16k, and their
        // skirts overlap, so the response there is the product of every filter
        // in the chain. A single band's gain is only readable when the others
        // are flat, which is what the bandGain tests above arrange.
        //
        // Predicting the product is what proves the whole curve was installed
        // rather than one band of it.
        graph.setEqBands(EqPresets.ROCK.bands)
        val at: Double = PRESET_PROBE_HZ

        var expected = 0.0
        for (band in EqPresets.ROCK.bands) {
            expected += 20.0 * log10(
                tv.nomercy.player.core.dsp.BiquadPeaking.magnitudeAt(
                    tv.nomercy.player.core.dsp.BiquadPeaking.coefficients(
                        band.frequency.toDouble(),
                        band.gainDb,
                        band.bandwidth,
                        SAMPLE_RATE,
                    ),
                    at,
                    SAMPLE_RATE,
                ),
            )
        }

        val measured: Double = shaped(at)

        assertTrue(
            abs(measured - expected) < TOLERANCE_DB,
            "the Rock curve predicts ${expected}dB at ${at}Hz, the graph gave ${measured}dB",
        )
    }

    @Test
    fun bypassingIsTransparentEvenWithACurveInPlace() {
        graph.bandGain(TONE.toInt(), BOOST_DB)
        graph.eqEnabled(false)

        assertTrue(abs(shaped(TONE)) < LEAK_DB, "a bypassed chain still filtered")
    }

    @Test
    fun theSpectrumTapSeesTheToneThatWasPlayed() {
        var frame: VisualizationFrame? = null
        graph.installFrameTap { frame = it }

        graph.shapeSamples(tone(BASS_TONE), FFT_SIZE)

        val seen: VisualizationFrame = assertNotNull(frame, "no frame reached the tap")
        assertTrue(seen.bandEnergies.bass > seen.bandEnergies.treble, "80Hz did not light bass: ${seen.bandEnergies}")
    }

    @Test
    fun appleReachesTheSameNumbersAsTheSharedFilter() {
        // The cross-platform claim. If this drifts, the same settings sound
        // different on an iPhone than on a desktop, and it is not reproducible
        // without both in the same room.
        graph.bandGain(TONE.toInt(), BOOST_DB)
        val throughGraph: Double = shaped(TONE)

        val direct: Double = 20.0 * log10(
            tv.nomercy.player.core.dsp.BiquadPeaking.magnitudeAt(
                tv.nomercy.player.core.dsp.BiquadPeaking.coefficients(TONE, BOOST_DB, 1.0, SAMPLE_RATE),
                TONE,
                SAMPLE_RATE,
            ),
        )

        assertEquals(direct, throughGraph, absoluteTolerance = TOLERANCE_DB)
    }
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val FFT_SIZE = 4_096
private const val AMPLITUDE = 0.1
private const val TONE = 1_000.0
private const val FAR_TONE = 10_000.0
private const val BASS_TONE = 80.0
private const val BOOST_DB = 12.0

// A band centre, so the probe sits where the curve is doing the most.
private const val PRESET_PROBE_HZ = 12_000.0
private const val TOLERANCE_DB = 1.5
private const val LEAK_DB = 1.0
