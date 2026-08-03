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
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import tv.nomercy.player.core.plugin.fakes.RecordingLogger
import tv.nomercy.player.core.plugin.fakes.RecordingStorage
import tv.nomercy.player.core.ports.Logger
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
    var firstReady: Int = 0

    override fun use() {
        on(EventKey<Int>("ping")) { pings += it }
    }

    fun listenOnceForReady(): Subscription = once(EventKey<Int>("ready")) { firstReady += it }

    fun listenForExtras(): Subscription = on(EventKey<Int>("extra")) { extras += it }

    fun fireBare() = emit(EventKey<String>("tick"), "x")
    fun fireOwnRegistryKey() = emit(Events.Line, "lyric")
    suspend fun writeTheme() = storage.set("theme", "dark")
    suspend fun readTheme(): String? = storage.get("theme")
    fun logHello() = logger.info("hello")
    fun logDetail() = logger.trace("frame 41 decoded")
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
    fun aTraceLineReachesALoggerThatOnlyImplementsTheFourOlderLevels() = runTest {
        // RecordingLogger predates trace and overrides nothing for it, which is
        // every Logger a consumer already wrote. The line has to arrive
        // somewhere rather than being dropped by a default that does nothing.
        val logger = RecordingLogger("[nmplayer]")
        val host = FakePluginHost(rootLogger = logger)
        val plugin = DemoPlugin()
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.logDetail()

        assertEquals(listOf("[nmplayer][demo] DEBUG frame 41 decoded"), logger.lines.toList())
    }

    @Test
    fun aLoggerThatSeparatesTraceFromDebugGetsItOnItsOwnChannel() = runTest {
        val lines: MutableList<String> = mutableListOf()
        val logger = object : Logger {
            override fun error(message: String, vararg args: Any?) { lines.add("ERROR $message") }
            override fun warn(message: String, vararg args: Any?) { lines.add("WARN $message") }
            override fun info(message: String, vararg args: Any?) { lines.add("INFO $message") }
            override fun debug(message: String, vararg args: Any?) { lines.add("DEBUG $message") }
            override fun trace(message: String, vararg args: Any?) { lines.add("TRACE $message") }
            override fun child(scope: String): Logger = this
        }
        val plugin = DemoPlugin()
        wire(plugin, FakePluginHost(rootLogger = logger), CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.logDetail()

        assertEquals(listOf("TRACE frame 41 decoded"), lines.toList())
    }

    @Test
    fun aOneShotSubscriptionFiresOnceAndDisposesItself() = runTest {
        val host = FakePluginHost()
        val plugin = DemoPlugin()
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.listenOnceForReady()
        host.emit(EventKey<Int>("ready"), 1)
        host.emit(EventKey<Int>("ready"), 10)

        // The accumulated value, not a call counter: 1 proves the first payload
        // arrived AND the second did not, where a count of 1 would also be
        // satisfied by a helper that dropped the first and kept the second.
        assertEquals(1, plugin.firstReady)
    }

    @Test
    fun aOneShotSubscriptionThatNeverFiresStillLeavesWithThePlugin() = runTest {
        val host = FakePluginHost()
        val plugin = DemoPlugin()
        val lifecycle = wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.listenOnceForReady()
        lifecycle.dispose()
        host.emit(EventKey<Int>("ready"), 1)

        assertEquals(0, plugin.firstReady)
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
    fun usingAPluginBeforeItIsRegisteredIsACodedErrorNamingThePlugin() = runTest {
        val plugin = DemoPlugin()

        val failure = assertFailsWith<PlayerError> { plugin.writeTheme() }

        assertEquals(PluginErrorCodes.STATE_UNINITIALIZED, failure.code)
        assertEquals("demo", failure.scope.id)
        assertTrue(failure.message.orEmpty().contains("demo"))
    }
}
