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
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPreset
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.FakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What survives closing the app, and what a listener saved themselves.
//
// The curve is the only setting in the player a listener builds by hand, one
// slider at a time. Losing it is not the same as losing a volume level: it is
// losing work, and the second time it happens they stop bothering.
class EqualizerPersistenceTest {

    private val custom = EqPreset(
        name = "Late night",
        bands = EqBands.DEFAULT.map { band -> band.copy(gainDb = -3.0) },
    )

    @Test
    fun aCustomPresetJoinsTheCatalogueAndCanBeChosenByName() = runTest {
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)

        equalizer.addCustomPreset(custom)
        equalizer.preset("Late night")

        assertTrue(equalizer.presets().any { it.name == "Late night" }, "the saved preset never joined the list")
        assertEquals(custom.bands, equalizer.bands())
        assertEquals("Late night", equalizer.preset())
    }

    // Built-ins are the floor everything else is compared against. A catalogue a
    // viewer can empty is one they can lock themselves out of.
    @Test
    fun aSavedPresetCanBeRemovedAndABuiltInCannot() = runTest {
        val equalizer = EqualizerPlugin(FakeDspGraph())
        equalizer.addCustomPreset(custom)

        equalizer.removePreset("Late night")
        equalizer.removePreset(EqPresets.ROCK.name)

        assertFalse(equalizer.presets().any { it.name == "Late night" })
        assertTrue(equalizer.presets().any { it.name == EqPresets.ROCK.name }, "a built-in was removable")
    }

    @Test
    fun theCurveTheHeadroomAndTheSavedPresetsAllComeBackNextLaunch() = runTest {
        val storage = FakeStorage()

        val first = EqualizerPlugin(FakeDspGraph(), EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(first)
        first.preset("Rock")
        first.preGain(2.0)
        first.addCustomPreset(custom)
        runCurrent()

        val graph = FakeDspGraph()
        val second = EqualizerPlugin(graph, EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(second)
        runCurrent()

        assertEquals(EqPresets.ROCK.bands, second.bands(), "the curve did not survive the restart")
        assertEquals("Rock", second.preset())
        assertEquals(3.0, second.preGain(), "the headroom came back without the curve that needed it")
        assertTrue(second.presets().any { it.name == "Late night" }, "the saved preset was lost")

        // Reaching the graph, not only the plugin's own field. A restore that
        // stopped at the state would show the right sliders over the wrong sound.
        assertEquals(EqPresets.ROCK.bands, graph.installedBands)
    }

    // The key carries the plugin id because the base class prefixes it, which is
    // what stops two plugins that both store "state" overwriting each other.
    @Test
    fun theCurveIsStoredUnderThePluginsOwnNamespace() = runTest {
        val storage = FakeStorage()
        val equalizer = EqualizerPlugin(FakeDspGraph(), EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(equalizer)

        equalizer.preset("Rock")
        runCurrent()

        assertTrue(
            storage.entries.containsKey("nmplayer-equalizer-state"),
            "written under ${storage.entries.keys}",
        )
    }

    @Test
    fun withNoKeyNothingIsWrittenAtAll() = runTest {
        val storage = FakeStorage()
        val equalizer = EqualizerPlugin(FakeDspGraph())
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(equalizer)

        equalizer.preset("Rock")
        runCurrent()

        assertTrue(storage.entries.isEmpty(), "persistence was on by default: ${storage.entries.keys}")
    }

    // A host that resets on launch still wants what the listener does written
    // down, or the setting is gone the moment they change the option back.
    @Test
    fun loadingCanBeSwitchedOffWithoutSwitchingOffSaving() = runTest {
        val storage = FakeStorage()
        val opts = EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY, autoLoad = false)

        val first = EqualizerPlugin(FakeDspGraph(), opts)
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(first)
        first.preset("Rock")
        runCurrent()

        val second = EqualizerPlugin(FakeDspGraph(), opts)
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(second)
        runCurrent()

        val flat: List<EqBand> = EqBands.DEFAULT
        assertEquals(flat, second.bands(), "autoLoad was ignored")
        assertTrue(storage.entries.containsKey("nmplayer-equalizer-state"), "nothing was written")
    }

    @Test
    fun savingCanBeSwitchedOffWithoutLosingTheKey() = runTest {
        val storage = FakeStorage()
        val equalizer = EqualizerPlugin(
            FakeDspGraph(),
            EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY, autoSave = false),
        )
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(equalizer)

        equalizer.preset("Rock")
        runCurrent()

        assertTrue(storage.entries.isEmpty(), "autoSave was ignored: ${storage.entries.keys}")
    }

    // A scope of its own rather than the test's own. A plugin's launch runs on
    // whatever the registry was given, and handing it the test scope makes runTest
    // wait at the end for a registry job that never completes — which reads as a
    // hung suite rather than as the wrong scope.
    private fun TestScope.pluginScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))
}
