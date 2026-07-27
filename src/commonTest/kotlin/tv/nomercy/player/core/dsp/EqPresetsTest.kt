// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000
private val FREQUENCIES = listOf(70, 180, 320, 600, 1_000, 3_000, 6_000, 12_000, 14_000, 16_000)

class EqPresetsTest {

    @Test
    fun theBandLayoutIsTheWebPlayers() {
        // A listener who set up an equaliser on the web and opened the app
        // should find the same sliders in the same places.
        assertEquals(FREQUENCIES, EqBands.DEFAULT.map { it.frequency })
        assertTrue(EqBands.DEFAULT.all { it.gainDb == 0.0 && it.bandwidth == DEFAULT_BANDWIDTH })
    }

    @Test
    fun everyBuiltInPresetIsThere() {
        assertEquals(19, EqPresets.BUILTIN.size)
        assertTrue(EqPresets.BUILTIN.map { it.name }.contains("Rock"))
        assertTrue(EqPresets.BUILTIN.map { it.name }.contains("Full Bass & Treble"))
    }

    @Test
    fun everyPresetCoversEveryBand() {
        // A preset missing a band leaves that slider wherever the last preset
        // put it, so switching presets would carry a value across.
        for (preset in EqPresets.BUILTIN) {
            assertEquals(
                FREQUENCIES,
                preset.bands.map { it.frequency },
                "preset ${preset.name} does not cover the standard bands",
            )
        }
    }

    @Test
    fun customIsFlat() {
        // The starting point. A "Custom" that already coloured the sound would
        // make every adjustment relative to something nobody chose.
        assertTrue(EqPresets.CUSTOM.bands.all { it.gainDb == 0.0 })
    }

    @Test
    fun presetsAreFoundByNameCaseInsensitively() {
        // Names come from stored settings and from a UI, and the two disagree
        // about capitalisation more often than they should.
        assertEquals(EqPresets.ROCK, EqPresets.byName("rock"))
        assertEquals(EqPresets.ROCK, EqPresets.byName("Rock"))
        assertNull(EqPresets.byName("Not A Preset"))
    }

    @Test
    fun aPresetsGainsSurviveTheTripThroughTheFilter() {
        // The presets are only worth having if the numbers reach the audio. This
        // takes the loudest band of a preset, builds the filter from it, and
        // checks the filter gives that gain back.
        val rock: EqPreset = EqPresets.ROCK
        val loudest: EqBand = rock.bands.maxBy { it.gainDb }

        val coefficients: BiquadCoefficients = BiquadPeaking.coefficients(
            frequencyHz = loudest.frequency.toDouble(),
            gainDb = loudest.gainDb,
            bandwidth = loudest.bandwidth,
            sampleRate = SAMPLE_RATE,
        )
        val measured: Double = 20.0 * log10(
            BiquadPeaking.magnitudeAt(coefficients, loudest.frequency.toDouble(), SAMPLE_RATE),
        )

        assertTrue(
            abs(measured - loudest.gainDb) < 0.2,
            "the Rock preset asked for ${loudest.gainDb} dB at ${loudest.frequency}Hz and got $measured dB",
        )
    }

    @Test
    fun noPresetAsksForMoreThanTheFilterCanGive() {
        // A preset beyond the sliders' range would be unreachable from the UI
        // and unrepresentable in stored settings.
        for (preset in EqPresets.BUILTIN) {
            for (band in preset.bands) {
                assertTrue(
                    band.gainDb in -12.0..12.0,
                    "preset ${preset.name} asks for ${band.gainDb} dB at ${band.frequency}Hz",
                )
            }
        }
    }

    @Test
    fun theCentrePreGainSliderIsUnity() {
        // Zero on the slider is a multiplier of one, not of zero. Treating the
        // value as decibels would make the resting position a boost.
        assertEquals(1.0, EqBands.preGainLinear(0.0))
    }

    @Test
    fun theTopOfThePreGainSliderQuadruples() {
        assertEquals(4.0, EqBands.preGainLinear(3.0))
    }

    @Test
    fun theMiddleOfThePreGainSliderIsSticky() {
        // Unity is the position a viewer returns to, and a pointer or a D-pad
        // lands on 0.004 rather than 0. Left alone that is a permanent,
        // inaudible offset on everything they play, and no amount of nudging
        // removes it — the one value the control cannot reach is the one it is
        // meant to rest at. The web snaps within the same window.
        assertEquals(1.0, EqBands.preGainLinear(0.004))
        assertEquals(1.0, EqBands.preGainLinear(-0.049))
    }

    @Test
    fun aDeliberateSmallAdjustmentSurvivesTheDetent() {
        // The detent has to be narrow enough that someone who meant it is not
        // overruled. Just outside the window is left exactly where it was put.
        assertEquals(1.06, EqBands.preGainLinear(0.06), absoluteTolerance = 1e-9)
    }

    @Test
    fun theBottomOfThePreGainSliderIsSilenceAndNotInversion() {
        // A negative multiplier inverts the waveform. Inaudible alone, and a
        // comb filter the moment it meets another copy of the same signal.
        assertEquals(0.0, EqBands.preGainLinear(-1.0))
        assertEquals(0.0, EqBands.preGainLinear(-5.0))
    }
}
