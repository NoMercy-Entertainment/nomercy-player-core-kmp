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
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import tv.nomercy.player.core.plugin.fakes.RecordingLogger
import tv.nomercy.player.core.plugin.fakes.RecordingStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class DemoOptions(val label: String)

private class DemoPlugin(private val authorDefault: DemoOptions? = null) : Plugin<DemoOptions>() {
    companion object Manifest : PluginManifest {
        override val id: String = "demo"
        override val version: String = "1.0.0"
    }

    object Events {
        val Line: EventKey<String> = pluginEventKey(Manifest, "line")
    }

    override val manifest: PluginManifest get() = Manifest
    override val options: DemoOptions? get() = authorDefault

    var pings: Int = 0
    var extras: Int = 0

    override fun use() {
        on(EventKey<Int>("ping")) { pings += it }
    }

    fun listenForExtras(): Subscription = on(EventKey<Int>("extra")) { extras += it }

    fun fireBare() = emit(EventKey<String>("tick"), "x")
    fun fireOwnRegistryKey() = emit(Events.Line, "lyric")
    fun writeTheme() = storage.set("theme", "dark")
    fun readTheme(): String? = storage.get("theme")
    fun logHello() = logger.info("hello")
    fun currentOptions(): DemoOptions? = resolvedOptions
}

class PluginBaseTest {

    private fun wire(
        plugin: DemoPlugin,
        host: FakePluginHost,
        scope: CoroutineScope,
        opts: DemoOptions? = null,
    ): LifecycleRegistry {
        val lifecycle = LifecycleRegistry(scope)
        plugin.initialize(host, opts, lifecycle)
        return lifecycle
    }

    @Test
    fun theLoggerAndStorageAPluginSeesAreAlreadyScopedToIt() = runTest {
        val storage = RecordingStorage()
        val host = FakePluginHost(rootLogger = RecordingLogger("[nmplayer]"), rootStorage = storage)
        val plugin = DemoPlugin()
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.writeTheme()
        plugin.logHello()

        // The key on disk carries the plugin id; the key the plugin used did not.
        assertEquals("dark", storage.map["nmplayer-demo-theme"])
        assertNull(storage.map["theme"])
        assertEquals("dark", plugin.readTheme())
        assertTrue((host.rootLogger as RecordingLogger).lines.any { it.startsWith("[nmplayer][demo]") })
    }

    @Test
    fun aBareEmitKeyIsNamespacedAndAnAlreadyScopedOneIsNotDoublePrefixed() = runTest {
        val host = FakePluginHost()
        val plugin = DemoPlugin()
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fireBare()
        plugin.fireOwnRegistryKey()

        val expected: List<Pair<String, Any?>> = listOf(
            "plugin:demo:tick" to "x",
            "plugin:demo:line" to "lyric",
        )
        assertEquals(expected, host.emitted.toList())
    }

    @Test
    fun aSubscriptionStopsDeliveringOnceThePluginIsTornDown() = runTest {
        val host = FakePluginHost()
        val plugin = DemoPlugin()
        val lifecycle = wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        plugin.use()

        host.emit(EventKey<Int>("ping"), 1)
        assertEquals(1, plugin.pings)

        lifecycle.dispose()
        host.emit(EventKey<Int>("ping"), 5)

        // The outcome, not the fact that a cleanup was registered.
        assertEquals(1, plugin.pings)
    }

    @Test
    fun aSubscriptionCanBeDisposedEarlyAndTeardownDisposingItAgainIsHarmless() = runTest {
        val host = FakePluginHost()
        val plugin = DemoPlugin()
        val lifecycle = wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val subscription = plugin.listenForExtras()

        subscription.dispose()
        host.emit(EventKey<Int>("extra"), 1)
        assertEquals(0, plugin.extras)

        // Teardown disposes it a second time. Idempotent disposal is what keeps
        // that from removing whatever registered after it.
        plugin.listenForExtras()
        lifecycle.dispose()
        host.emit(EventKey<Int>("extra"), 1)

        assertEquals(0, plugin.extras)
    }

    @Test
    fun consumerOptionsWinOverTheAuthorsDefault() = runTest {
        val host = FakePluginHost()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        val defaulted = DemoPlugin(authorDefault = DemoOptions("author"))
        wire(defaulted, host, scope)
        assertEquals(DemoOptions("author"), defaulted.currentOptions())

        val overridden = DemoPlugin(authorDefault = DemoOptions("author"))
        wire(overridden, host, scope, opts = DemoOptions("consumer"))
        assertEquals(DemoOptions("consumer"), overridden.currentOptions())
    }

    @Test
    fun usingAPluginBeforeItIsRegisteredSaysWhichPluginItWas() {
        val plugin = DemoPlugin()

        val failure = assertFailsWith<IllegalStateException> { plugin.writeTheme() }

        assertTrue(failure.message.orEmpty().contains("demo"))
    }
}
