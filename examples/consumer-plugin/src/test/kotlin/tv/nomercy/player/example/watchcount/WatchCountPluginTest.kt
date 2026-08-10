// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.example.watchcount

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

// Tested with only the published `testing` artifact — `FakePlayer`, `testPlugin`
// — the same way `HelloPluginTest` proves the scaffold. This project never
// imports the library's source tree, so a green run here is the acceptance:
// the shipped surface is enough to write AND test a real plugin.
class WatchCountPluginTest {

    @Test
    fun countsEachPlayAndLeavesNothingBehind() = runTest {
        val player = FakePlayer()
        var lastCount = 0
        player.on(WatchCountEvents.Changed) { lastCount = it }

        val result = testPlugin(WatchCountPlugin(), player = player) { host, _ ->
            repeat(3) { host.emit(CoreEvents.Play, PlaySource("user")) }
        }

        assertEquals(3, lastCount)
        assertEquals(0, result.leaked)
    }

    @Test
    fun theEventIsNamespacedToThePlugin() {
        assertEquals("plugin:watch-count:changed", WatchCountEvents.Changed.name)
    }
}
