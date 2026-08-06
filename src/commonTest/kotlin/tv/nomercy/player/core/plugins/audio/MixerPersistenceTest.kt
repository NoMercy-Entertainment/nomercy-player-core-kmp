// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.FakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Gain, pan and mute across a restart.
//
// Mute is the one that matters most and the easiest to drop: an app that comes
// back unmuted plays sound into a room the listener silenced on purpose.
class MixerPersistenceTest {

    @Test
    fun gainAndMuteComeBackNextLaunch() = runTest {
        val storage = FakeStorage()
        val opts = MixerOptions(persistKey = DEFAULT_PERSIST_KEY)

        val first = MixerPlugin(RecordingGraph(), opts)
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(first)
        first.gain(-6.0)
        first.muted(true)
        runCurrent()

        val graph = RecordingGraph()
        val second = MixerPlugin(graph, opts)
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(second)
        runCurrent()

        assertEquals(-6.0, second.gain(), "the gain did not survive the restart")
        assertTrue(second.muted(), "the app came back unmuted into a room somebody silenced")

        // Reaching the graph, not only the plugin's field: a restore that stopped
        // at the state would show a muted control over audible sound.
        assertEquals(0.0, graph.preGains.last())
    }

    @Test
    fun theMixerIsStoredUnderItsOwnNamespace() = runTest {
        val storage = FakeStorage()
        val mixer = MixerPlugin(RecordingGraph(), MixerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(mixer)

        mixer.gain(3.0)
        runCurrent()

        assertTrue(
            storage.entries.containsKey("nmplayer-mixer-state"),
            "written under ${storage.entries.keys}",
        )
    }

    @Test
    fun withNoKeyNothingIsWrittenAtAll() = runTest {
        val storage = FakeStorage()
        val mixer = MixerPlugin(RecordingGraph())
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(mixer)

        mixer.gain(3.0)
        runCurrent()

        assertTrue(storage.entries.isEmpty(), "persistence was on by default: ${storage.entries.keys}")
    }

    // A scope of its own rather than the test's own. A plugin's launch runs on
    // whatever the registry was given, and handing it the test scope makes runTest
    // wait at the end for a registry job that never completes — which reads as a
    // hung suite rather than as the wrong scope.
    private fun TestScope.pluginScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))
}
