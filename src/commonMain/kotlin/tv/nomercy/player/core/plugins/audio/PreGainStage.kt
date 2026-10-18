// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.ports.AudioDspGraph

// The graph has one pre-gain node and two plugins want it. Each keeps its own
// factor here and the node gets their product, so neither undoes the other.
internal class PreGainStage(private val graph: AudioDspGraph) {
    private var mixer: Double = 1.0
    private var equalizer: Double = 1.0
    private var users: Int = 0

    fun setMixer(linear: Double) {
        mixer = linear
        graph.preGain(mixer * equalizer)
    }

    fun setEqualizer(linear: Double) {
        equalizer = linear
        graph.preGain(mixer * equalizer)
    }

    companion object {
        private val stages: MutableList<PreGainStage> = mutableListOf()

        fun acquire(graph: AudioDspGraph): PreGainStage {
            val stage: PreGainStage = stages.firstOrNull { it.graph === graph }
                ?: PreGainStage(graph).also { stages += it }
            stage.users++
            return stage
        }

        fun release(stage: PreGainStage) {
            if (--stage.users <= 0) stages.remove(stage)
        }
    }
}
