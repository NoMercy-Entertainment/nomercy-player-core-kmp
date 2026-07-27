// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The reference visualiser, and the one reduction everybody gets wrong.
class WaveformVisualizationTest {

    private fun graphWith(samples: DoubleArray): Pair<FakeDspGraph, WaveformVisualization> {
        val graph = FakeDspGraph()
        val visualiser = WaveformVisualization(SpectrumPlugin(graph), columns = COLUMNS)
        visualiser.start()
        graph.pushFrame(frameOf(samples))
        return graph to visualiser
    }

    @Test
    fun aLoudWaveformDoesNotReduceToAFlatLine() {
        // The mistake this exists to prevent. Averaging a waveform gives nearly
        // zero however loud it is, because the positive and negative halves
        // cancel — so an averaging reduction draws a flat line through the
        // middle of the loudest music and looks like silence.
        val loud = DoubleArray(SAMPLES) { index -> sin(2.0 * PI * index / CYCLE) }

        val (_, visualiser) = graphWith(loud)

        assertEquals(COLUMNS, visualiser.columns().size)
        assertTrue(
            visualiser.columns().all { it > 0.5 },
            "a full-scale tone reduced to ${visualiser.columns().take(4)}",
        )
    }

    @Test
    fun silenceReducesToSilence() {
        val (_, visualiser) = graphWith(DoubleArray(SAMPLES))

        assertTrue(visualiser.columns().all { it == 0.0 })
    }

    @Test
    fun everyColumnIsPositiveBecauseItIsAnAmplitude() {
        // A column is how far the signal went, not which way. A negative one
        // draws below the baseline and turns a waveform into noise.
        val mixed = DoubleArray(SAMPLES) { index -> if (index % 2 == 0) -0.8 else 0.4 }

        val (_, visualiser) = graphWith(mixed)

        assertTrue(visualiser.columns().all { it >= 0.0 })
        assertTrue(visualiser.columns().all { it > 0.7 }, "the negative half was dropped rather than measured")
    }

    @Test
    fun aWaveformShorterThanTheViewIsNotStretched() {
        // Fewer samples than columns is what a small FFT gives. Inventing
        // columns to fill the width would be drawing data that does not exist.
        val short = DoubleArray(4) { 0.5 }

        val (_, visualiser) = graphWith(short)

        assertEquals(4, visualiser.columns().size)
    }

    @Test
    fun aChromeCanBeToldRatherThanHavingToPoll() {
        val graph = FakeDspGraph()
        val visualiser = WaveformVisualization(SpectrumPlugin(graph), columns = COLUMNS)
        var told = 0
        visualiser.onColumns { told++ }
        visualiser.start()

        graph.pushFrame(frameOf(DoubleArray(SAMPLES) { 0.5 }))

        assertEquals(1, told)
    }

    private fun frameOf(samples: DoubleArray): VisualizationFrame = VisualizationFrame(
        frequency = DoubleArray(samples.size / 2),
        waveform = samples,
        time = 0.0,
        deltaMs = 16.0,
        energy = 0.0,
        bandEnergies = BandEnergies(0.0, 0.0, 0.0),
        sampleRate = 48_000,
        binHz = 11.7,
        peakHz = 0.0,
        peakBandEnergies = BandEnergies(0.0, 0.0, 0.0),
    )
}

private const val COLUMNS = 16
private const val SAMPLES = 512
private const val CYCLE = 32.0
