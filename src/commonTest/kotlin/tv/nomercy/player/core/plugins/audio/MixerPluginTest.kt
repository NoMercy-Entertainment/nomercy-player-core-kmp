// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.AudioDspGraph
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Records what reached the graph, because that is the only thing a listener
// hears. Asserting the plugin's own field would pass on a mixer wired to
// nothing.
//
// The empty overrides are the point of a recorder rather than an oversight: the
// mixer touches preGain and nothing else, and a stand-in that did something for
// the rest would be inventing behaviour the plugin never asked for.
@Suppress("EmptyFunctionBlock")
private class RecordingGraph : AudioDspGraph {
    val preGains: MutableList<Double> = mutableListOf()

    override fun setEqBands(bands: List<EqBand>) {}

    override fun bandGain(frequencyHz: Int, gainDb: Double) {}

    override fun preGain(linear: Double) {
        preGains += linear
    }

    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription =
        Subscription {}

    override fun removeFrameTap() {}

    override fun eqEnabled(enabled: Boolean) {}
}

class MixerPluginTest {

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) < 0.001, "$what: expected ~$expected, got $actual")
    }

    @Test
    fun unityGainReachesTheGraphAsOne() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            assertClose(1.0, graph.preGains.last(), "unity")
        }
    }

    // Amplitude, not power: +6 dB is roughly double. Using 10 rather than 20 in
    // the conversion halves every boost a listener asks for, which is the
    // classic way to get this wrong and sounds merely quiet rather than broken.
    @Test
    fun sixDecibelsIsAboutDouble() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.gain(6.0)
            assertClose(1.995, graph.preGains.last(), "+6 dB")
        }
    }

    @Test
    fun minusSixDecibelsIsAboutHalf() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.gain(-6.0)
            assertClose(0.501, graph.preGains.last(), "-6 dB")
        }
    }

    // A slider dragged past its end stops at the end rather than throwing at
    // whoever is holding it.
    @Test
    fun gainIsClampedRatherThanRefused() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph, MixerOptions(maxGainDb = 12.0))

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.gain(400.0)
            assertEquals(12.0, plugin.gain())

            plugin.gain(-400.0)
            assertEquals(-12.0, plugin.gain())
        }
    }

    @Test
    fun panIsClampedToPlusOrMinusOne() {
        val plugin = MixerPlugin(RecordingGraph(), panSupported = true)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.pan(9.0)
            assertEquals(1.0, plugin.pan())

            plugin.pan(-9.0)
            assertEquals(-1.0, plugin.pan())
        }
    }

    // A mixer reporting a pan it never applied is a control a listener moves
    // while nothing changes.
    @Test
    fun aGraphThatCannotPanSaysSo() {
        val plugin = MixerPlugin(RecordingGraph(), panSupported = false)
        var reported: Double? = null

        testPlugin(plugin, FakePlayer()) { player, _ ->
            // Disposed inside the exercise, because the harness counts
            // listeners either side of teardown and a test's own subscription
            // left open is indistinguishable from the plugin leaking one.
            val watching = player.on(MixerEvents.PanUnsupportedOnPlayer) { reported = it }

            plugin.pan(0.5)
            assertEquals(0.5, reported)

            watching.dispose()
        }
    }

    // The difference between a mute button and a reset button.
    @Test
    fun unmutingRestoresTheChosenGainRatherThanUnity() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.gain(6.0)
            plugin.mute(true)
            assertClose(0.0, graph.preGains.last(), "muted")

            plugin.mute(false)
            assertClose(1.995, graph.preGains.last(), "unmuted")
            assertEquals(6.0, plugin.gain())
        }
    }

    @Test
    fun theStartingValuesReachTheGraphOnInstall() {
        val graph = RecordingGraph()
        val plugin = MixerPlugin(graph, MixerOptions(gainDb = -6.0))

        testPlugin(plugin, FakePlayer()) { _, _ ->
            assertClose(0.501, graph.preGains.last(), "initial -6 dB")
        }
    }
}
