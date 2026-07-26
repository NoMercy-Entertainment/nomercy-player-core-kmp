// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.controllers.FakeMediaBackend
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PluginDisabledPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Each fixture declares its manifest the way a real plugin must: a companion
// object, fixed at the class rather than passed in. The priorities are the
// point of the ordering tests, so they are named for what they are.
private open class Fixture(private val declared: PluginManifest) : Plugin<Unit>() {
    override val manifest: PluginManifest get() = declared
    var storedCalls: Int = 0
}

private class ChaptersPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "chapters"
        override val version: String = "1.0.0"
    }
}

private class HighPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "high"
        override val version: String = "1.0.0"
        override val priority: Int = 100
    }
}

private class MiddlePlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "middle"
        override val version: String = "1.0.0"
        override val priority: Int = 50
    }
}

private class LowPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "low"
        override val version: String = "1.0.0"
    }
}

// Three at the same priority, for the tie-break order.
private class FirstPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "first"
        override val version: String = "1.0.0"
        override val priority: Int = 10
    }
}

private class SecondPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "second"
        override val version: String = "1.0.0"
        override val priority: Int = 10
    }
}

private class ThirdPlugin : Fixture(Manifest) {
    companion object Manifest : PluginManifest {
        override val id: String = "third"
        override val version: String = "1.0.0"
        override val priority: Int = 10
    }
}

// Enabling and disabling a plugin without unloading it.
//
// The distinction under test is that disable is not remove: a settings toggle
// that lost the plugin's state every time it was flipped would make "turn
// subtitles off for this show" forget the font size the viewer picked.
class PluginEnableDisableTest {

    private suspend fun playerWith(vararg plugins: Plugin<Unit>): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend()).also { player ->
            plugins.forEach { player.addPlugin(it) }
        }

    @Test
    fun aPluginIsEnabledOnceItIsRegistered() = runTest {
        val plugin = ChaptersPlugin()

        playerWith(plugin)

        assertTrue(plugin.enabled())
    }

    @Test
    fun aDisabledPluginLeavesTheRegistryButNotTheEnabledList() = runTest {
        val plugin = ChaptersPlugin()
        val player: ComposedPlayer = playerWith(plugin)

        plugin.disable()

        assertFalse(plugin.enabled())
        assertEquals(emptyList(), player.enabledPlugins().map { it.id })
        assertEquals(listOf("chapters"), player.pluginList().map { it.id }, "disabling removed the plugin")
        assertTrue(player.getPluginById("chapters") != null, "a disabled plugin became unreachable")
    }

    @Test
    fun enablingAgainRestoresTheSameInstance() = runTest {
        // The whole reason disable exists rather than remove-and-re-add: the
        // plugin that comes back is the one that was there, with what it knew.
        val plugin = ChaptersPlugin()
        val player: ComposedPlayer = playerWith(plugin)
        plugin.storedCalls = 7

        plugin.disable()
        plugin.enable()

        assertEquals(listOf("chapters"), player.enabledPlugins().map { it.id })
        assertEquals(7, (player.getPluginById("chapters") as Fixture).storedCalls)
    }

    @Test
    fun disablingSaysWhyAndBothListenersHearIt() = runTest {
        val plugin = ChaptersPlugin()
        val player: ComposedPlayer = playerWith(plugin)
        val everyPlugin: MutableList<String?> = mutableListOf()
        val justThisOne: MutableList<String?> = mutableListOf()
        player.on(CoreEvents.PluginDisabled) { everyPlugin += it.reason }
        player.on(EventKey<PluginDisabledPayload>("plugin:chapters:disabled")) { justThisOne += it.reason }

        plugin.disable("the chapter service did not answer")

        val expected: List<String?> = listOf("the chapter service did not answer")
        assertEquals(expected, everyPlugin)
        assertEquals(expected, justThisOne)
    }

    @Test
    fun disablingTwiceAnnouncesOnce() = runTest {
        // A UI that re-renders on plugin:disabled should not do it twice
        // because a caller was defensive.
        val plugin = ChaptersPlugin()
        val player: ComposedPlayer = playerWith(plugin)
        var announcements = 0
        player.on(CoreEvents.PluginDisabled) { announcements += 1 }

        plugin.disable()
        plugin.disable()

        assertEquals(1, announcements)
    }

    @Test
    fun theEnabledListRunsHighestPriorityFirst() = runTest {
        // The order is the reason this list exists: a before-dispatch chain
        // walks it, and a plugin that must veto before another one says so with
        // its priority rather than by hoping the consumer registers it first.
        val player: ComposedPlayer = playerWith(
            LowPlugin(),
            HighPlugin(),
            MiddlePlugin(),
        )

        assertEquals(listOf("high", "middle", "low"), player.enabledPlugins().map { it.id })
    }

    @Test
    fun equalPrioritiesKeepRegistrationOrder() = runTest {
        // Ties must not reorder between calls. A chrome rendering its slots
        // from this list would otherwise shuffle its own buttons.
        val player: ComposedPlayer = playerWith(
            FirstPlugin(),
            SecondPlugin(),
            ThirdPlugin(),
        )

        assertEquals(listOf("first", "second", "third"), player.enabledPlugins().map { it.id })
        assertEquals(player.enabledPlugins().map { it.id }, player.enabledPlugins().map { it.id })
    }
}
