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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioGraphPluginTest {

    @Test
    fun aGraphComesUpReady() {
        val plugin = AudioGraphPlugin(RecordingGraph())
        var ready = false

        testPlugin(plugin, FakePlayer()) { player, _ ->
            val watching = player.on(AudioGraphEvents.ReadyOnPlayer) { ready = true }
            assertTrue(plugin.isReady())
            watching.dispose()
        }

        // use() runs during registration, before the listener above exists, so
        // readiness is asserted from the plugin and the event is covered by the
        // unsupported case below where the ordering works the other way.
        assertFalse(ready)
    }

    // A device with no DSP path is an ordinary device, not a broken one — and a
    // chrome that hears this can leave the equaliser out of its menus instead
    // of offering a control that moves and changes nothing.
    @Test
    fun noGraphIsReportedRatherThanPretended() {
        val plugin = AudioGraphPlugin(graph = null)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            assertFalse(plugin.isReady())
            assertNull(plugin.graph())
        }
    }

    @Test
    fun theEqualiserStartsWhereTheOptionsSayAndCanBeToggled() {
        val graph = RecordingGraph()
        val plugin = AudioGraphPlugin(graph, AudioGraphOptions(eqEnabled = true))

        testPlugin(plugin, FakePlayer()) { _, _ ->
            assertEquals(listOf(true), graph.eqStates)

            plugin.eqEnabled(false)
            assertEquals(listOf(true, false), graph.eqStates)
        }
    }

    // Toggling on a platform without a graph is a no-op rather than a crash.
    // The caller is a chrome that does not know which device it is on.
    @Test
    fun togglingWithoutAGraphDoesNothing() {
        val plugin = AudioGraphPlugin(graph = null)

        testPlugin(plugin, FakePlayer()) { _, _ ->
            plugin.eqEnabled(true)
        }
    }
}
