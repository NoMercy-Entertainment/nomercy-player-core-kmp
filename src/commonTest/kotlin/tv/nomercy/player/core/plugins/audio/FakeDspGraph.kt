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

// A graph that records what it was asked to do instead of doing it.
//
// The arithmetic is tested elsewhere against known signals; what is untestable
// without a device is the plumbing — whether a preset reaches the chain in one
// call, whether a slider drag reaches one band, whether a tap that was disposed
// stops receiving frames. A fake is the right tool for that and the wrong one
// for the numbers.
public class FakeDspGraph : AudioDspGraph {

    public var installedBands: List<EqBand> = emptyList()
        private set

    // Every single-band update in order, because the ordering is the part that
    // matters: a chain rebuilt on each drag would show up here as repeated
    // setEqBands rather than as these.
    public val bandUpdates: MutableList<Pair<Int, Double>> = mutableListOf()

    public var preGain: Double = 1.0
        private set

    public var enabled: Boolean = true
        private set

    public var setEqBandsCalls: Int = 0
        private set

    // How many times a tap was installed, not how many are live. The
    // difference is the leak: a consumer that opens a second tap without
    // disposing the first leaves the graph running an FFT nobody reads, and
    // counting only live ones would show that as one.
    public var tapInstalls: Int = 0
        private set

    private var tap: ((VisualizationFrame) -> Unit)? = null

    override fun setEqBands(bands: List<EqBand>) {
        setEqBandsCalls++
        installedBands = bands
    }

    override fun bandGain(frequencyHz: Int, gainDb: Double) {
        bandUpdates += frequencyHz to gainDb
    }

    override fun preGain(linear: Double) {
        preGain = linear
    }

    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription {
        tapInstalls++
        tap = onFrame
        return Subscription { if (tap === onFrame) tap = null }
    }

    override fun removeFrameTap() {
        tap = null
    }

    override fun eqEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    // Drives the tap the way a real graph would, so a consumer can be tested
    // without an audio device.
    public fun pushFrame(frame: VisualizationFrame) {
        tap?.invoke(frame)
    }

    public fun hasTap(): Boolean = tap != null
}
