// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.Fft
import tv.nomercy.player.core.dsp.HannWindow
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The desktop equaliser, measured the same way Android's is.
//
// The point of this file is that the two answers match. Both platforms run
// core's biquad rather than the engine's own equaliser, so a preset chosen on
// one has to land on the same frequencies with the same gains on the other —
// otherwise a viewer moving between their desktop and their phone hears the
// same title differently with the same settings.
//
// libVLC is not involved. A class that reached for it could not be tested
// without installing it, and the arithmetic is the part worth measuring; the
// plumbing that hands it samples is exercised by the engine gates next door.
class PcmEqualiserTest {

    private fun tone(hz: Double, frames: Int = FFT_SIZE): FloatArray {
        val samples = FloatArray(frames * CHANNELS)
        for (frame in 0 until frames) {
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

    private fun equaliser() = PcmEqualiser(SAMPLE_RATE, CHANNELS)

    @Test
    fun boostingABandRaisesItByTheNumberItWasGiven() {
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), BOOST_DB)

        val samples: FloatArray = tone(TONE)
        val before: Double = levelDb(tone(TONE), TONE)
        equaliser.process(samples, FFT_SIZE)
        val measured: Double = levelDb(samples, TONE) - before

        assertTrue(abs(measured - BOOST_DB) < TOLERANCE_DB, "asked for ${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun cuttingABandLowersItByTheNumberItWasGiven() {
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), -BOOST_DB)

        val samples: FloatArray = tone(TONE)
        val before: Double = levelDb(tone(TONE), TONE)
        equaliser.process(samples, FFT_SIZE)
        val measured: Double = levelDb(samples, TONE) - before

        assertTrue(abs(measured + BOOST_DB) < TOLERANCE_DB, "asked for -${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun aFlatChainIsTransparent() {
        // The default has to leave the sound alone, or every listener hears the
        // equaliser whether or not they ever opened it.
        val equaliser: PcmEqualiser = equaliser()
        val samples: FloatArray = tone(TONE)
        val before: Double = levelDb(tone(TONE), TONE)

        equaliser.process(samples, FFT_SIZE)

        assertTrue(abs(levelDb(samples, TONE) - before) < LEAK_DB, "a flat chain changed the level")
    }

    @Test
    fun aBandLeavesTheRestOfTheSpectrumAlone() {
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), BOOST_DB)

        val samples: FloatArray = tone(FAR_TONE)
        val before: Double = levelDb(tone(FAR_TONE), FAR_TONE)
        equaliser.process(samples, FFT_SIZE)

        assertTrue(abs(levelDb(samples, FAR_TONE) - before) < LEAK_DB, "a 1kHz boost moved 10kHz")
    }

    @Test
    fun theDesktopAgreesWithTheSharedArithmetic() {
        // The claim that matters across platforms: this is core's biquad, not a
        // second implementation that happens to be close. Same tone, same band,
        // same gain — the measured result must match what the shared filter
        // gives on its own.
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), BOOST_DB)

        val samples: FloatArray = tone(TONE)
        equaliser.process(samples, FFT_SIZE)
        val throughGraph: Double = levelDb(samples, TONE) - levelDb(tone(TONE), TONE)

        val direct: Double = 20.0 * log10(
            tv.nomercy.player.core.dsp.BiquadPeaking.magnitudeAt(
                tv.nomercy.player.core.dsp.BiquadPeaking.coefficients(TONE, BOOST_DB, 1.0, SAMPLE_RATE),
                TONE,
                SAMPLE_RATE,
            ),
        )

        assertTrue(abs(throughGraph - direct) < TOLERANCE_DB, "graph gave ${throughGraph}dB, filter says ${direct}dB")
    }

    @Test
    fun theSpectrumTapSeesTheToneThatWasPlayed() {
        val equaliser: PcmEqualiser = equaliser()
        var frame: VisualizationFrame? = null
        equaliser.installFrameTap { frame = it }

        equaliser.process(tone(BASS_TONE), FFT_SIZE)

        val seen: VisualizationFrame = assertNotNull(frame, "no frame reached the tap")
        assertTrue(seen.bandEnergies.bass > seen.bandEnergies.treble, "80Hz did not light bass: ${seen.bandEnergies}")
    }

    @Test
    fun bypassingIsTransparentEvenWithACurveInPlace() {
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), BOOST_DB)
        equaliser.eqEnabled(false)

        val samples: FloatArray = tone(TONE)
        val before: Double = levelDb(tone(TONE), TONE)
        equaliser.process(samples, FFT_SIZE)

        assertTrue(abs(levelDb(samples, TONE) - before) < LEAK_DB, "a bypassed chain still filtered")
    }

    @Test
    fun outputStaysInsideFullScale() {
        // libVLC's amem buffer is float and a boosted band genuinely exceeds
        // one. Letting it through hands the sound card a sample it cannot
        // represent, which is the loudest noise the hardware can make.
        val equaliser: PcmEqualiser = equaliser()
        equaliser.bandGain(TONE.toInt(), BOOST_DB)
        equaliser.preGain(4.0)

        val samples: FloatArray = tone(TONE)
        equaliser.process(samples, FFT_SIZE)

        assertTrue(samples.all { it in -1.0f..1.0f }, "a sample escaped full scale")
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
private const val TOLERANCE_DB = 1.5
private const val LEAK_DB = 1.0
