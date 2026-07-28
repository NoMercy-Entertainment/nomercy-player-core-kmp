// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The blessed path: every listener through `this.on`, which the base class
// unsubscribes when the plugin goes away.
private class TidyPlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "tidy"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    var seen: Int = 0

    override fun use() {
        on(CoreEvents.Play) { seen += 1 }
        on(CoreEvents.Pause) { seen += 1 }
    }
}

// The one the harness has to catch: it reaches around the base class to the raw
// bus, so nothing unsubscribes it. This is not a contrived shape — it is what a
// plugin author writes the first time they need a listener the helpers do not
// obviously cover, and it is why the harness exists.
private class LeakyPlugin(private val host: FakePlayer) : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "leaky"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override fun use() {
        host.on(CoreEvents.Play) { }
    }
}

class LeakHarnessTest {

    @Test
    fun aTidyPluginPassesAndTheNumbersAreReported() = runTest {
        val result: LeakAssertionResult = testPlugin(TidyPlugin()) { player, plugin ->
            player.emit(CoreEvents.Play, PlaySource("test"))
            assertEquals(1, plugin.seen)
        }

        assertEquals("tidy", result.subjectId)
        assertEquals(0, result.leaked)
        assertTrue(
            result.listenersAfterSetup > result.listenersBefore,
            "the plugin registered nothing, so this proves nothing",
        )
    }

    // The proof the harness has teeth. Without a failing case, "no leaks
    // detected" is indistinguishable from "nothing was measured".
    @Test
    fun aLeakyPluginIsRejected() = runTest {
        val failure: AssertionError = assertFailsWith {
            val player = FakePlayer()
            testPlugin(LeakyPlugin(player), player = player)
        }

        assertTrue(
            failure.message.orEmpty().contains("[leak-harness]"),
            "the failure did not name itself: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("leaky"),
            "the failure did not name the plugin: ${failure.message}",
        )
    }

    // A leak that only appears on the third registration is the interesting
    // kind: it survives a single-shot check and shows up in production a week
    // later.
    @Test
    fun aLeakThatOnlyAppearsAfterSeveralCyclesIsCaught() = runTest {
        val player = FakePlayer()
        var registrations = 0

        assertFailsWith<AssertionError> {
            assertNoListenerLeakOverCycles(
                subjectId = "third-time",
                player = player,
                setup = {
                    registrations += 1
                    if (registrations >= 3) player.on(EventKey<Unit>("stray")) { }
                },
                teardown = { },
            )
        }
    }

    @Test
    fun aSubjectThatCleansUpOverEveryCyclePasses() = runTest {
        val player = FakePlayer()
        var subscription: tv.nomercy.player.core.events.Subscription? = null

        val results: List<LeakAssertionResult> = assertNoListenerLeakOverCycles(
            subjectId = "balanced",
            player = player,
            setup = { subscription = player.on(EventKey<Unit>("ping")) { } },
            teardown = { subscription?.dispose() },
        )

        assertEquals(5, results.size)
        assertTrue(results.all { it.leaked == 0 })
    }
}
