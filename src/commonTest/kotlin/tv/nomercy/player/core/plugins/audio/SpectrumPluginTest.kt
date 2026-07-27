// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Frame distribution, which is where the leaks are.
//
// The arithmetic is proven elsewhere. What breaks here is plumbing: a second
// tap orphaning the first, a listener removed from inside its own callback, a
// plugin torn down while a view still holds a subscription. Each one is silent
// and each one costs an FFT per frame forever.
class SpectrumPluginTest {

    @Test
    fun oneTapServesEveryListener() {
        // Each visualiser opening its own tap means the same FFT run several
        // times a frame, which on a phone is the difference between a
        // visualiser and a warm battery.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        val first: MutableList<Double> = mutableListOf()
        val second: MutableList<Double> = mutableListOf()

        spectrum.onFrame { first += it.time }
        spectrum.onFrame { second += it.time }
        graph.pushFrame(frameAt(1.0))

        assertEquals(listOf(1.0), first)
        assertEquals(listOf(1.0), second, "the second listener never got a frame")

        // The count, not just that both listeners were fed. Reopening the tap
        // per listener still delivers to everyone — the old tap is simply
        // orphaned in the graph, running its FFT with nobody reading it. That
        // is invisible from the listener side, which is why this asserts on the
        // graph: the first version of this test passed with the leak in place.
        assertEquals(1, graph.tapInstalls, "a second tap was opened and the first was left running")
    }

    @Test
    fun aListenerThatRemovesItselfMidFrameDoesNotBreakTheOthers() {
        // Exactly what a view does when it is detached while a frame is being
        // delivered. Walking the live list would throw or skip the next one.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        val survivor: MutableList<Double> = mutableListOf()
        var handle: Subscription? = null

        handle = spectrum.onFrame { handle?.dispose() }
        spectrum.onFrame { survivor += it.time }

        graph.pushFrame(frameAt(1.0))
        graph.pushFrame(frameAt(2.0))

        assertEquals(listOf(1.0, 2.0), survivor, "a self-removing listener took another one with it")
    }

    @Test
    fun theLatestFrameIsAvailableToAnythingDrawingOnItsOwnClock() {
        // A visualiser driven by the display's refresh asks rather than waits,
        // and the two rates are never the same.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        spectrum.onFrame { }

        assertNull(spectrum.currentFrame(), "a frame appeared before any arrived")
        graph.pushFrame(frameAt(1.0))
        graph.pushFrame(frameAt(2.0))

        assertEquals(2.0, spectrum.currentFrame()?.time)
    }

    @Test
    fun aPushedFrameIsRefusedUnlessSyntheticModeIsOn() {
        // A stray push would make a visualiser show something that was never in
        // the audio, and a viewer watching a display that does not match what
        // they hear has no way to tell which of the two is wrong.
        val spectrum = SpectrumPlugin(FakeDspGraph())
        val seen: MutableList<Double> = mutableListOf()
        spectrum.onFrame { seen += it.time }

        spectrum.pushFrame(frameAt(9.0))
        assertTrue(seen.isEmpty(), "a frame was injected without synthetic mode")

        spectrum.syntheticMode(true)
        spectrum.pushFrame(frameAt(9.0))

        assertEquals(listOf(9.0), seen)
    }

    @Test
    fun syntheticModeSuppressesTheRealSpectrumRatherThanRacingIt() {
        // Both arriving means a visualiser alternates between the audio and the
        // stand-in every frame, which reads as a display that cannot make up
        // its mind. The web drops tap frames while the mode is on.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        val seen: MutableList<Double> = mutableListOf()
        spectrum.onFrame { seen += it.time }
        spectrum.syntheticMode(true)

        graph.pushFrame(frameAt(1.0))
        spectrum.pushFrame(frameAt(9.0))

        assertEquals(listOf(9.0), seen, "a tap frame got through while synthetic mode was on")
    }

    @Test
    fun leavingSyntheticModeThrowsTheStandInAway() {
        // Left behind it would still answer currentFrame() after the real
        // spectrum came back, so a visualiser polling it draws a shape the
        // audio never had.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        spectrum.onFrame { }
        spectrum.syntheticMode(true)
        spectrum.pushFrame(frameAt(9.0))

        spectrum.syntheticMode(false)
        graph.pushFrame(frameAt(2.0))

        assertEquals(2.0, spectrum.currentFrame()?.time, "the synthetic frame outlived the mode")
    }

    @Test
    fun disposingStopsTheGraphEvenWhileAListenerRemains() {
        // A host tearing the plugin down while a view still holds a
        // subscription must still stop the graph running an FFT into nothing.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        spectrum.onFrame { }
        assertTrue(graph.hasTap())

        spectrum.dispose()

        assertFalse(graph.hasTap(), "the graph is still producing frames nobody reads")
        assertNull(spectrum.currentFrame())
    }

    @Test
    fun bandEnergyBeforeAnyFrameIsZeroRatherThanAGuess() {
        val spectrum = SpectrumPlugin(FakeDspGraph())

        assertEquals(0.0, spectrum.bandEnergy(20.0, 250.0))
    }

    @Test
    fun withNoGraphItReportsUnavailableAndStaysQuiet() {
        // The video engines have no DSP graph, and a host registering this
        // everywhere should not have to special-case them.
        val spectrum = SpectrumPlugin(null)
        val seen: MutableList<Double> = mutableListOf()

        assertFalse(spectrum.available())
        spectrum.onFrame { seen += it.time }

        assertTrue(seen.isEmpty())
        assertNull(spectrum.currentFrame())
    }

    @Test
    fun aVisualiserStartedTwiceStillRendersEachFrameOnce() {
        // A view attached, detached and attached again calls start twice, and a
        // second subscription shows up as a visualiser moving at double speed.
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        val visualiser = CountingVisualization(spectrum)

        visualiser.start()
        visualiser.start()
        graph.pushFrame(frameAt(1.0))

        assertEquals(1, visualiser.rendered)
        assertTrue(visualiser.running())
    }

    @Test
    fun aStoppedVisualiserRendersNothing() {
        val graph = FakeDspGraph()
        val spectrum = SpectrumPlugin(graph)
        val visualiser = CountingVisualization(spectrum)
        visualiser.start()

        visualiser.stop()
        graph.pushFrame(frameAt(1.0))

        assertEquals(0, visualiser.rendered)
        assertFalse(visualiser.running())
    }

    private fun frameAt(seconds: Double): VisualizationFrame = VisualizationFrame(
        frequency = DoubleArray(FRAME_BINS),
        waveform = DoubleArray(FRAME_BINS),
        time = seconds,
        deltaMs = 16.0,
        energy = 0.0,
        bandEnergies = BandEnergies(0.0, 0.0, 0.0),
        sampleRate = 48_000,
        binHz = 11.7,
        peakHz = 0.0,
        peakBandEnergies = BandEnergies(0.0, 0.0, 0.0),
    )
}

private const val FRAME_BINS = 8

private class CountingVisualization(spectrum: SpectrumPlugin) : VisualizationPlugin(spectrum) {
    companion object Manifest : PluginManifest {
        override val id: String = "counting-visualization"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    var rendered: Int = 0
        private set

    override fun render(frame: VisualizationFrame) {
        rendered++
    }
}
