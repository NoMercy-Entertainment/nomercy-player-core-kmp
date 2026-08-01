// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.ErrorCode
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.ScopeKind
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.cues.Cue
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeState
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SurfacePlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "surface"
        override val version: String = "1.0.0"
    }

    object Events {
        val BeforeDraw: EventKey<BeforeEvent<Int>> = pluginEventKey(Manifest, "beforeDraw")
    }

    override val manifest: PluginManifest get() = Manifest

    fun boom(): Nothing = throwError("surface:draw/boom", "boom")
    fun warn() = report("surface:draw/soft", "soft")
    fun openSocket(): RealtimeChannel = websocket("wss://example.test")
    suspend fun call(): FetchResponse = fetch("https://example.test/x", FetchOptions(method = "POST"))
    fun label(): String = t("play", mapOf("count" to "2"))
    fun scheduleLate(flag: BooleanArray) = timeout(1_000) { flag[0] = true }
    suspend fun askBeforeDrawing(value: Int) = dispatchBefore(Events.BeforeDraw, value)
    fun findCueParser(url: String): CueParser<*>? = resolveCueParser(url)
}

// advanceTimeBy/runCurrent drive the virtual clock these tests measure
// cancellation against.
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSurfaceTest {

    private fun wire(host: FakePluginHost, scope: CoroutineScope): Pair<SurfacePlugin, LifecycleRegistry> {
        val lifecycle = LifecycleRegistry(scope)
        val plugin = SurfacePlugin()
        plugin.initialize(host, null, lifecycle)
        return plugin to lifecycle
    }

    @Test
    fun throwErrorStopsTheFlowAndStillTellsTheHost() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        val error = assertFailsWith<PlayerError> { plugin.boom() }

        assertEquals("surface:draw/boom", error.code)
        assertEquals(ScopeKind.PLUGIN, error.scope.kind)
        assertEquals("surface", error.scope.id)
        assertEquals(Severity.ERROR, error.severity)
        // Thrown and reported: a host that catches nothing still sees it.
        assertEquals(1, host.reported.size)
    }

    @Test
    fun anErrorAPluginMintsCarriesItsOwnNamespaceAndStillParses() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.warn()

        val code = ErrorCode.parse(host.reported.single().code)
        assertEquals("surface", code.namespace)
    }

    @Test
    fun reportSurfacesTheProblemWithoutStopping() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.warn()

        assertEquals("surface:draw/soft", host.reported.single().code)
        assertEquals(Severity.WARNING, host.reported.single().severity)
    }

    @Test
    fun aSocketClosesItselfWhenThePluginGoesAway() = runTest {
        val host = FakePluginHost()
        val (plugin, lifecycle) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val channel = plugin.openSocket()
        assertEquals(RealtimeState.OPEN, channel.readyState)

        lifecycle.dispose()

        assertEquals(RealtimeState.CLOSED, channel.readyState)
    }

    @Test
    fun fetchGoesThroughTheHostSoAuthIsNotThePluginsProblem() = runTest {
        val host = FakePluginHost()
        host.nextFetch = FetchResponse(status = 204, body = "ok")
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        val response = plugin.call()

        assertEquals(204, response.status)
        assertEquals("https://example.test/x" to FetchOptions(method = "POST"), host.fetched.single())
    }

    @Test
    fun aTranslationKeyIsNamespacedUnderThePluginId() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        assertEquals("plugin.surface.play", plugin.label())
        assertEquals(mapOf("count" to "2"), host.translated.single().second)
    }

    @Test
    fun aScheduledCallbackDiesWithThePlugin() = runTest {
        val host = FakePluginHost()
        val (plugin, lifecycle) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val flag = booleanArrayOf(false)
        plugin.scheduleLate(flag)

        lifecycle.dispose()
        advanceTimeBy(2_000)
        runCurrent()

        assertFalse(flag[0])
    }

    @Test
    fun aPluginGetsTheSameCancellableSeamCoreDoes() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        host.bus.on(SurfacePlugin.Events.BeforeDraw) { event ->
            event.data = 99
            event.preventDefault()
        }

        val result = plugin.askBeforeDrawing(1)

        assertTrue(result.prevented)
        assertEquals(99, result.data)
    }

    // A bare host resolves nothing — the PluginHost default, not a plugin's own
    // opinion about what "nothing registered" means.
    @Test
    fun aPluginWithNoHostRegistryResolvesNothing() = runTest {
        val host = FakePluginHost()
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        assertEquals(null, plugin.findCueParser("song.lrc"))
    }

    // The gap this closes: a plugin used to carry its own CueParserRegistry and
    // could not see a format the HOST had registered. Wiring the fake host with
    // a resolver and reaching it through the plugin's protected helper is the
    // same path LyricsPlugin now takes through `resolveCueParser()` — this
    // fails without the PluginHost seam or the Plugin-base forwarding method.
    @Test
    fun aPluginSeesTheHostsRegisteredParserNotAPrivateOne() = runTest {
        val karaoke = object : CueParser<String> {
            override val id: String = "fillz:karaoke"
            override fun canParse(url: String, contentType: String?): Boolean = url.endsWith(".karaoke")
            override fun parse(raw: String, baseUrl: String?): List<Cue<String>> = emptyList()
        }
        val host = FakePluginHost(cueParserResolver = { url, _ -> karaoke.takeIf { url.endsWith(".karaoke") } })
        val (plugin, _) = wire(host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        assertEquals("fillz:karaoke", plugin.findCueParser("song.karaoke")?.id)
    }
}
