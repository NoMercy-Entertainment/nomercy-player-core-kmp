// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.template

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.LeakAssertionResult
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What a plugin's test looks like when the shipped harness is doing the work.
//
// One call registers the plugin through the real registry, hands it back, tears
// it down, and asserts it left no listener behind. The leak check is free: an
// author writing this test gets it whether or not they were thinking about
// teardown, which is the only way a leak check gets run on the plugins that
// need it.
class HelloPluginTest {

    @Test
    fun itGreetsOnPlayAndLeavesNothingBehind() = runTest {
        val player = FakePlayer()
        var greetings = 0
        player.on(HelloEvents.Greeted) { greetings += 1 }

        val result: LeakAssertionResult = testPlugin(HelloPlugin(), player = player) { host, _ ->
            host.emit(CoreEvents.Play, PlaySource("user"))
        }

        assertEquals(1, greetings)
        assertEquals(0, result.leaked)
    }

    // The event goes out namespaced, so another plugin can listen for it by the
    // same key and nothing on the global channel collides with it.
    @Test
    fun theEventIsNamespacedToThePlugin() {
        assertEquals("plugin:hello:greeted", HelloEvents.Greeted.name)
    }

    // A disabled plugin keeps its listeners and stops acting. That is what a
    // settings toggle needs, and it is the base class doing it rather than
    // anything written here.
    @Test
    fun disablingItStopsTheGreetingWithoutUnregisteringIt() = runTest {
        val player = FakePlayer()
        val plugin = HelloPlugin()
        var greetings = 0
        player.on(HelloEvents.Greeted) { greetings += 1 }

        testPlugin(plugin, player = player) { host, subject ->
            host.emit(CoreEvents.Play, PlaySource("user"))
            subject.disable("testing")
            host.emit(CoreEvents.Play, PlaySource("user"))
        }

        assertEquals(1, greetings)
    }

    // The greeting is the plugin's own, in the viewer's language, looked up
    // under plugin.hello. so it cannot shadow a core string.
    @Test
    fun theGreetingComesFromTheTranslator() = runTest {
        val player = FakePlayer()
        player.translations["plugin.hello.greeting"] = "Goedendag"
        var said: String? = null
        player.on(HelloEvents.Greeted) { said = it.text }

        testPlugin(HelloPlugin(), player = player) { host, _ ->
            host.emit(CoreEvents.Play, PlaySource("user"))
        }

        assertEquals("Goedendag", said)
    }

    // Options a consumer passes at registration beat the author's defaults.
    @Test
    fun registrationOptionsWinOverTheAuthorsDefaults() = runTest {
        val player = FakePlayer()
        var said: String? = null
        player.on(HelloEvents.Greeted) { said = it.text }

        testPlugin(HelloPlugin(), player = player, options = HelloOptions(greeting = "Oi")) { host, _ ->
            host.emit(CoreEvents.Play, PlaySource("user"))
        }

        assertTrue(said == "Oi", "the registered greeting was ignored: $said")
    }
}
