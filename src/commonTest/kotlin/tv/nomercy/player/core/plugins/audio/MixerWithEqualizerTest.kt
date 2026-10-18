// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

// Both plugins drive the one pre-gain node, so neither may undo the other.
class MixerWithEqualizerTest {

    @Test
    fun aPresetKeepsTheMixerMuted() {
        val graph = RecordingGraph()
        val mixer = MixerPlugin(graph)
        val equalizer = EqualizerPlugin(graph)

        testPlugin(mixer, FakePlayer()) { _, _ ->
            mixer.muted(true)

            equalizer.preset("Rock")

            assertEquals(0.0, graph.preGains.last(), "a preset un-muted the mixer")
        }
    }

    @Test
    fun aResetKeepsTheMixerMuted() {
        val graph = RecordingGraph()
        val mixer = MixerPlugin(graph)
        val equalizer = EqualizerPlugin(graph)

        testPlugin(mixer, FakePlayer()) { _, _ ->
            mixer.muted(true)

            equalizer.reset()

            assertEquals(0.0, graph.preGains.last(), "a reset un-muted the mixer")
        }
    }
}
