// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.AudioDspGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The seam between a plugin and wherever the sound actually is.
//
// These are the plumbing rules the arithmetic tests cannot reach: that a preset
// arrives as one call rather than ten, that a tap survives being disposed
// exactly once, that a bypass is a bypass. Each has a specific way of going
// wrong that a listener hears and a unit test of the biquad never would.
class AudioDspGraphTest {

    @Test
    fun aPresetArrivesAsOneChangeRatherThanTen() {
        // Ten separate updates let a listener hear the chain reshape band by
        // band — a swoop as the curve is built. Choosing a preset is one
        // decision and should sound like one.
        val graph = FakeDspGraph()

        graph.setEqBands(EqPresets.ROCK.bands)

        assertEquals(1, graph.setEqBandsCalls)
        assertEquals(EqPresets.ROCK.bands, graph.installedBands)
        assertTrue(graph.bandUpdates.isEmpty(), "a preset leaked out as per-band updates")
    }

    @Test
    fun aSliderDragMovesOneBandAndDoesNotRebuildTheChain() {
        // The other half of the same rule. Rebuilding on every drag frame drops
        // the filter state and clicks sixty times a second.
        val graph = FakeDspGraph()
        graph.setEqBands(EqBands.DEFAULT)

        graph.bandGain(frequencyHz = 1_000, gainDb = 4.5)
        graph.bandGain(frequencyHz = 1_000, gainDb = 5.0)

        assertEquals(listOf(1_000 to 4.5, 1_000 to 5.0), graph.bandUpdates)
        assertEquals(1, graph.setEqBandsCalls, "a slider drag rebuilt the whole chain")
    }

    @Test
    fun aFrameTapReceivesFramesUntilItIsDisposed() {
        val graph = FakeDspGraph()
        val seen: MutableList<Double> = mutableListOf()
        val tap: Subscription = graph.installFrameTap { frame -> seen += frame.time }

        graph.pushFrame(frameAt(1.0))
        tap.dispose()
        graph.pushFrame(frameAt(2.0))

        assertEquals(listOf(1.0), seen, "a disposed tap kept receiving frames")
        assertFalse(graph.hasTap())
    }

    @Test
    fun disposingATapTwiceIsHarmless() {
        // A plugin torn down twice is ordinary — a host that stops a player and
        // then releases it does exactly that. The second dispose must not take
        // out a tap somebody else installed in between.
        val graph = FakeDspGraph()
        val first: Subscription = graph.installFrameTap { }
        first.dispose()

        val second: Subscription = graph.installFrameTap { }
        first.dispose()

        assertTrue(graph.hasTap(), "disposing a dead subscription removed the live one")
        second.dispose()
        assertFalse(graph.hasTap())
    }

    @Test
    fun bypassIsSeparateFromAFlatCurve() {
        // Flat still runs ten biquads over every sample to change nothing,
        // which on a phone is battery spent for no audible reason. Off has to
        // be reachable without moving the sliders.
        val graph: AudioDspGraph = FakeDspGraph()
        graph.setEqBands(EqBands.DEFAULT)
        graph.eqEnabled(false)

        val fake: FakeDspGraph = graph as FakeDspGraph
        assertFalse(fake.enabled)
        assertEquals(EqBands.DEFAULT, fake.installedBands, "bypassing threw the curve away")
    }

    @Test
    fun preGainIsAMultiplierAndUnityIsOne() {
        // Boosting ten bands at once overflows the signal, and this is the
        // headroom that stops it clipping. Read as decibels, the resting
        // position would be a boost.
        val graph = FakeDspGraph()

        graph.preGain(EqBands.preGainLinear(0.0))

        assertEquals(1.0, graph.preGain)
    }

    private fun frameAt(seconds: Double): VisualizationFrame = VisualizationFrame(
        frequency = DoubleArray(1),
        waveform = DoubleArray(1),
        time = seconds,
        deltaMs = 16.0,
        energy = 0.0,
        bandEnergies = BandEnergies(0.0, 0.0, 0.0),
        sampleRate = 48_000,
        binHz = 1.0,
        peakHz = 0.0,
        peakBandEnergies = BandEnergies(0.0, 0.0, 0.0),
    )
}
