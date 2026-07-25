// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private open class Named(name: String, private val disposeOrder: MutableList<String>) : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = name
        override val version: String = "1.0.0"
    }

    var used: Boolean = false
    var beats: Int = 0

    override fun use() {
        used = true
        on(EventKey<Int>("beat")) { beats += it }
    }

    override fun dispose() {
        disposeOrder.add(id)
    }
}

private class Exploding(name: String, disposeOrder: MutableList<String>) : Named(name, disposeOrder) {
    override fun dispose() {
        super.dispose()
        error("dispose boom")
    }
}

class PluginRegistryTest {

    private fun registry(host: FakePluginHost, scope: CoroutineScope) =
        PluginRegistry(host, coreVersion = "2.0.0", scope = scope)

    @Test
    fun registeringRunsUseAndAnnouncesTheInstall() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val plugin = Named("a", mutableListOf())

        registry.register(plugin)

        assertTrue(plugin.used)
        assertSame(plugin, registry.getById("a"))
        val installed = host.emitted.single { it.first == "plugin:installed" }.second as Map<*, *>
        assertEquals("a", installed["id"])
        assertEquals("1.0.0", installed["version"])
    }

    @Test
    fun disposeTearsDownInReverseRegistrationOrder() = runTest {
        val host = FakePluginHost()
        val order = mutableListOf<String>()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(Named("a", order))
        registry.register(Named("b", order))
        registry.register(Named("c", order))

        registry.dispose()

        // A dependency is always registered before whatever needs it, so going
        // backwards disposes dependents first.
        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun oneThrowingDisposeDoesNotStopTheOthers() = runTest {
        val host = FakePluginHost()
        val order = mutableListOf<String>()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(Named("a", order))
        registry.register(Exploding("b", order))
        registry.register(Named("c", order))

        registry.dispose()

        assertEquals(listOf("c", "b", "a"), order)
        assertEquals(PluginErrorCodes.DISPOSE_FAILED, host.reported.single().code)
    }

    @Test
    fun aPluginThatThrowsFromDisposeStillHasItsListenersTornDown() = runTest {
        val host = FakePluginHost()
        val order = mutableListOf<String>()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val plugin = Exploding("b", order)
        registry.register(plugin)

        host.emit(EventKey<Int>("beat"), 1)
        registry.dispose()
        host.emit(EventKey<Int>("beat"), 9)

        // The teardown after the throw is the part that matters: a plugin that
        // fails to clean up must not keep receiving events.
        assertEquals(1, plugin.beats)
    }

    @Test
    fun disposingTheRegistryAlsoSilencesEveryPluginsListeners() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val plugin = Named("a", mutableListOf())
        registry.register(plugin)

        host.emit(EventKey<Int>("beat"), 1)
        registry.dispose()
        host.emit(EventKey<Int>("beat"), 9)

        assertEquals(1, plugin.beats)
    }

    @Test
    fun removingOnePluginLeavesTheRestRunning() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val kept = Named("kept", mutableListOf())
        registry.register(Named("dropped", mutableListOf()))
        registry.register(kept)

        registry.remove("dropped")
        host.emit(EventKey<Int>("beat"), 1)

        assertNull(registry.getById("dropped"))
        assertEquals(1, kept.beats)
        assertTrue(host.emitted.any { it.first == "plugin:disposed" })
    }

    @Test
    fun registeringAfterDisposeIsRefusedAsALifecycleFault() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.dispose()

        val error = assertFailsWith<PlayerError> { registry.register(Named("late", mutableListOf())) }

        // Under core:lifecycle, not core:plugin — the plugin did nothing wrong.
        assertEquals("core:lifecycle/use-plugin-after-dispose", error.code)
    }

    @Test
    fun disposeIsIdempotent() = runTest {
        val host = FakePluginHost()
        val order = mutableListOf<String>()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(Named("a", order))

        registry.dispose()
        registry.dispose()

        assertEquals(listOf("a"), order)
        assertTrue(registry.isDisposed())
    }
}
