// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FannedGraphTest {

    private class RecordingGraph : AudioDspGraph {
        var bands: List<EqBand>? = null
        val gains: MutableMap<Int, Double> = mutableMapOf()
        var preGain: Double? = null
        var enabled: Boolean? = null
        var tap: ((VisualizationFrame) -> Unit)? = null

        override fun setEqBands(bands: List<EqBand>) {
            this.bands = bands
            gains.clear()
        }

        override fun bandGain(frequencyHz: Int, gainDb: Double) {
            gains[frequencyHz] = gainDb
        }

        override fun preGain(linear: Double) {
            preGain = linear
        }

        override fun eqEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription {
            tap = onFrame
            return Subscription { if (tap === onFrame) tap = null }
        }

        override fun removeFrameTap() {
            tap = null
        }
    }

    private val first = RecordingGraph()
    private val second = RecordingGraph()
    private var audible: RecordingGraph = first
    private var all: List<RecordingGraph> = listOf(first)
    private val graph = FannedGraph(targets = { all }, audible = { audible })

    @Test
    fun aGraphJoiningAfterTheCurveWasSetStartsWithThatCurve() {
        val curve = listOf(EqBand(frequency = 60, gainDb = 6.0))
        graph.setEqBands(curve)
        graph.bandGain(60, 9.0)
        graph.preGain(0.5)
        graph.eqEnabled(false)

        graph.adopt(second)

        assertEquals(curve, second.bands)
        assertEquals(9.0, second.gains[60])
        assertEquals(0.5, second.preGain)
        assertEquals(false, second.enabled)
    }

    @Test
    fun aGraphJoiningBeforeAnythingWasSetIsLeftAlone() {
        graph.adopt(second)

        assertNull(second.bands)
        assertNull(second.preGain)
        assertNull(second.enabled)
    }

    @Test
    fun theTapFollowsTheAudibleGraphWhenTheEnginesSwap() {
        val frames = mutableListOf<VisualizationFrame>()
        graph.installFrameTap { frames += it }
        all = listOf(first, second)

        audible = second
        graph.audibleChanged()

        assertNull(first.tap)
        assertTrue(second.tap != null)
    }

    @Test
    fun removingTheTapAfterTheSwapClearsTheAudibleGraph() {
        graph.installFrameTap { }
        all = listOf(first, second)
        audible = second
        graph.audibleChanged()

        graph.removeFrameTap()

        assertNull(second.tap)
    }

    @Test
    fun aSwapWithNoTapInstalledInstallsNothing() {
        audible = second
        all = listOf(first, second)

        graph.audibleChanged()

        assertFalse(second.tap != null)
        assertSame(second, audible)
    }
}
