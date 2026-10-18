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
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.FakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals

// An explicit restore() is the host's own call and must not depend on autoLoad.
class EqualizerRestoreTest {

    private val key = "nmplayer-equalizer-state"

    @Test
    fun restoreReadsTheStoredCurveEvenWhenAutoLoadIsOff() = runTest {
        val storage = FakeStorage()
        val writer = EqualizerPlugin(FakeDspGraph(), EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(writer)
        writer.preset("Rock")
        runCurrent()

        val reader = EqualizerPlugin(
            FakeDspGraph(),
            EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY, autoLoad = false),
        )
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(reader)
        runCurrent()
        reader.restore()

        assertEquals(EqPresets.ROCK.bands, reader.bands(), "restore() did nothing with autoLoad off")
    }

    @Test
    fun restoreIgnoresAStoredBlobWithNoBands() = runTest {
        val storage = FakeStorage(mapOf(key to "{}"))
        val equalizer = EqualizerPlugin(FakeDspGraph(), EqualizerOptions(persistKey = DEFAULT_PERSIST_KEY))
        FakePlayer(scope = pluginScope(), rootStorage = storage).plugins.register(equalizer)
        runCurrent()

        equalizer.restore()

        assertEquals(EqBands.DEFAULT, equalizer.bands(), "an empty blob flattened the curve")
    }

    private fun TestScope.pluginScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))
}
