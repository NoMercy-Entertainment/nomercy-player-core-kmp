// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The desktop's [AudioDspGraph] — EQ only, and that is a limit of mpv, not a
// choice made here.
//
// mpv's public client API exposes no raw-PCM tap: nothing plays the role
// libVLC's `amem` callback played for the plan this port was originally
// written against, and without one the shared
// [tv.nomercy.player.core.ports.PcmEqualiser] this project's biquad math
// otherwise runs through cannot see a sample. EQ moves to mpv's own `af`
// filter chain instead (see [MpvAudioFilterChain] for why that is a
// coordinate change and not a second implementation of the curve).
//
// Spectrum/visualization has no equivalent substitute — mpv's `af` graph can
// shape audio, but nothing in the public client API hands this process the
// samples a [tv.nomercy.player.core.plugins.audio.VisualizationFrame] needs.
// [installFrameTap] is therefore a real no-op, not a stub awaiting wiring:
// closing that gap needs either a custom native mpv filter or a second,
// parallel raw-decode path, neither of which this class can grow into.
public class MpvDspGraph(private val backend: MpvVideoBackend) : AudioDspGraph {

    private var bands: List<EqBand> = EqBands.DEFAULT
    private var preGain: Double = 1.0
    private var enabled: Boolean = true

    override fun setEqBands(bands: List<EqBand>) {
        this.bands = bands
        apply()
    }

    override fun bandGain(frequencyHz: Int, gainDb: Double) {
        val index: Int = bands.indexOfFirst { it.frequency == frequencyHz }
        if (index < 0) return
        bands = bands.toMutableList().also { it[index] = it[index].copy(gainDb = gainDb) }
        apply()
    }

    override fun preGain(linear: Double) {
        preGain = linear
        apply()
    }

    override fun eqEnabled(enabled: Boolean) {
        this.enabled = enabled
        apply()
    }

    // No sample access on this backend — see the class doc. Returns a
    // subscription that does nothing when disposed, the same contract a real
    // tap gives a caller that never receives a frame.
    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription = Subscription {}

    override fun removeFrameTap() {
        // Nothing was ever installed.
    }

    private fun apply() {
        backend.setAudioFilter(MpvAudioFilterChain.build(bands, preGain, enabled))
    }
}
