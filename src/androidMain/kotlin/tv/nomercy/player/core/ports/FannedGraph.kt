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

// Settings go to every engine's graph and are remembered, so an engine that
// joins later starts with the curve instead of the default. The frame tap goes
// to the audible graph only, and follows it when the engines swap.
internal class FannedGraph(
    private val targets: () -> List<AudioDspGraph>,
    private val audible: () -> AudioDspGraph,
) : AudioDspGraph {

    private var bands: List<EqBand>? = null
    private val gains: MutableMap<Int, Double> = linkedMapOf()
    private var gain: Double? = null
    private var enabled: Boolean? = null
    private var tap: ((VisualizationFrame) -> Unit)? = null
    private var tapped: AudioDspGraph? = null

    private fun each(action: (AudioDspGraph) -> Unit) = targets().forEach(action)

    fun adopt(graph: AudioDspGraph) {
        bands?.let(graph::setEqBands)
        gains.forEach { (frequency, db) -> graph.bandGain(frequency, db) }
        gain?.let(graph::preGain)
        enabled?.let(graph::eqEnabled)
    }

    fun audibleChanged() {
        val onFrame: (VisualizationFrame) -> Unit = tap ?: return
        tapped?.removeFrameTap()
        tapped = audible().also { it.installFrameTap(onFrame) }
    }

    override fun setEqBands(bands: List<EqBand>) {
        this.bands = bands
        gains.clear()
        each { it.setEqBands(bands) }
    }

    override fun bandGain(frequencyHz: Int, gainDb: Double) {
        gains[frequencyHz] = gainDb
        each { it.bandGain(frequencyHz, gainDb) }
    }

    override fun preGain(linear: Double) {
        gain = linear
        each { it.preGain(linear) }
    }

    override fun eqEnabled(enabled: Boolean) {
        this.enabled = enabled
        each { it.eqEnabled(enabled) }
    }

    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription {
        tap = onFrame
        tapped = audible().also { it.installFrameTap(onFrame) }
        return Subscription { if (tap === onFrame) removeFrameTap() }
    }

    override fun removeFrameTap() {
        tap = null
        tapped?.removeFrameTap()
        tapped = null
    }
}
