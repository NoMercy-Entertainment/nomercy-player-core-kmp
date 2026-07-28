// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.RealtimeEvent
import tv.nomercy.player.core.ports.RealtimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class GreetingPlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "greeting"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    var greetings: Int = 0

    override fun use() {
        on(CoreEvents.Play) { greetings += 1 }
    }
}

class FakesTest {

    // The spine is a real emitter, not a recording of one. A test double whose
    // on() only remembers the call would let a plugin that never fires look
    // exactly like one that does.
    @Test
    fun theEventSpineIsReal() {
        val player = FakePlayer()
        val ping: EventKey<Int> = EventKey("ping")
        var received: Int? = null

        val subscription = player.on(ping) { received = it }
        player.emit(ping, 7)
        assertEquals(7, received)

        subscription.dispose()
        player.emit(ping, 9)
        assertEquals(7, received)
    }

    // addPlugin runs the real registry, which means use() has run and the
    // plugin's listeners are live. A fake that only appended to a list would
    // pass a plugin test that never executed a line of the plugin.
    @Test
    fun addPluginRunsTheRealRegistrySoUseHasHappened() {
        val player = FakePlayer()
        val plugin = GreetingPlugin()

        assertSame(player, player.addPlugin(plugin))
        assertSame(plugin, player.getPlugin(GreetingPlugin::class))

        player.emit(CoreEvents.Play, tv.nomercy.player.core.events.PlaySource("test"))
        assertEquals(1, plugin.greetings)
    }

    // dispose() tears the registry down, so the plugin's scoped listeners go
    // with it. This is the property the leak harness measures.
    @Test
    fun disposeTakesThePluginsListenersWithIt() {
        val player = FakePlayer()
        val plugin = GreetingPlugin()
        player.addPlugin(plugin)

        val whileRegistered: Int = player.listenerCount()
        player.dispose()

        assertTrue(
            player.listenerCount() < whileRegistered,
            "dispose left ${player.listenerCount()} listeners, was $whileRegistered",
        )
    }

    @Test
    fun transportDrivesTheObservableStateAndBothViewsAgree() {
        val player = FakePlayer()

        player.play()

        assertEquals(player.state(), player.stateFlow.value)
        assertEquals(tv.nomercy.player.core.player.PlayerPhase.PLAYING, player.state().phase)
    }

    @Test
    fun theQueueCursorReadsBackThroughItemAndIndex() {
        val player = FakePlayer(listOf(TestItem("a"), TestItem("b")))

        assertEquals(-1, player.index())
        assertNull(player.item())

        player.next()

        assertEquals(0, player.index())
        assertEquals("a", player.item()?.id)
    }

    @Test
    fun fakeStorageRoundTripsAndForgets() = runTest {
        val storage = FakeStorage()

        storage.set("k", "v")
        assertEquals("v", storage.get("k"))

        storage.remove("k")
        assertNull(storage.get("k"))
    }

    // The channel records what a plugin sent and can be driven the other way,
    // because a plugin that only ever talks is half a test.
    @Test
    fun fakeRealtimeChannelRecordsSendsAndDeliversInbound() {
        val channel = FakeRealtimeChannel()
        var inbound: Any? = null
        channel.on(RealtimeEvent.MESSAGE) { inbound = it }

        channel.send("hello")
        channel.deliver(RealtimeEvent.MESSAGE, "world")

        assertEquals(listOf("hello"), channel.sentText)
        assertEquals("world", inbound)

        channel.close()
        assertEquals(RealtimeState.CLOSED, channel.readyState)
    }

    @Test
    fun fakeFetchAnswersQueuedResponsesInOrderAndRecordsTheCalls() = runTest {
        val fetcher = FakeFetcher()
        fetcher.respondWith(FetchResponse(status = 200, body = "first"))
        fetcher.respondWith(FetchResponse(status = 404, body = "second"))

        assertEquals("first", fetcher.fetch("https://example.test/a", FetchOptions()).body)
        assertEquals(404, fetcher.fetch("https://example.test/b", FetchOptions()).status)

        assertEquals(
            listOf("https://example.test/a", "https://example.test/b"),
            fetcher.calls.map { it.url },
        )
    }

    // An unqueued call fails rather than returning an empty 200. A plugin
    // cannot tell a stubbed empty response from a server that answered, so a
    // silent default would turn a missing arrangement into a passing test.
    @Test
    fun fakeFetchRefusesToInventAResponse() = runTest {
        assertFailsWith<PlayerError> {
            FakeFetcher().fetch("https://example.test/unqueued", FetchOptions())
        }
    }
}
