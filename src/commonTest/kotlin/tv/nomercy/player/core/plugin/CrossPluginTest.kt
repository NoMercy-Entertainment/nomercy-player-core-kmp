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
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Publishes an Events registry. Another plugin listens to it by referencing the
// object, never by typing the wire string.
private class Publisher : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "publisher"
        override val version: String = "1.0.0"
    }

    object Events {
        val Ping: EventKey<Int> = pluginEventKey(Manifest, "ping")
        val BeforePing: EventKey<BeforeEvent<Int>> = pluginEventKey(Manifest, "beforePing")
    }

    override val manifest: PluginManifest get() = Manifest

    fun ping(value: Int) = emit(Events.Ping, value)

    suspend fun askBeforePinging(value: Int) = dispatchBefore(Events.BeforePing, value)
}

private class Subscriber : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = "subscriber"
        override val version: String = "1.0.0"
        override val requires: List<Requirement> = listOf(Requirement(Publisher.Manifest))
    }

    var heard: MutableList<Int> = mutableListOf()
    var vetoNext: Boolean = false

    override fun use() {
        on(Publisher.Events.Ping) { heard.add(it) }
        on(Publisher.Events.BeforePing) { event ->
            event.data = event.data * 2
            if (vetoNext) event.preventDefault()
        }
    }
}

// Contributes to two slots so ordering has something to order.
private class ChromePlugin(
    id: String,
    priority: Int,
    private val slotOrder: Int,
    private val takesOver: Boolean = false,
) : Plugin<Unit>() {
    private val contribution = object : ChromeContribution {
        override val slot: ChromeSlot = ChromeSlot.Transport
        override val order: Int = slotOrder
        override val replaces: Boolean = takesOver
    }

    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = id
        override val version: String = "1.0.0"
        override val priority: Int = priority
        override val contributions: List<ChromeContribution> = listOf(contribution)
    }
}

class CrossPluginTest {

    private fun registry(host: FakePluginHost, scope: CoroutineScope) =
        PluginRegistry(host, coreVersion = "2.0.0", scope = scope)

    @Test
    fun onePluginHearsAnothersEventWithoutEitherTypingTheWireName() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val publisher = Publisher()
        val subscriber = Subscriber()
        registry.register(publisher)
        registry.register(subscriber)

        publisher.ping(7)

        assertEquals(listOf(7), subscriber.heard)
        assertTrue(host.emitted.any { it.first == "plugin:publisher:ping" })
    }

    @Test
    fun aSubscriberCanReshapeAPublishersBeforeEvent() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val publisher = Publisher()
        registry.register(publisher)
        registry.register(Subscriber())

        val result = publisher.askBeforePinging(21)

        assertFalse(result.prevented)
        assertEquals(42, result.data)
    }

    @Test
    fun aSubscriberCanRefuseAPublishersAction() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val publisher = Publisher()
        val subscriber = Subscriber()
        registry.register(publisher)
        registry.register(subscriber)
        subscriber.vetoNext = true

        val result = publisher.askBeforePinging(21)

        assertTrue(result.prevented)
        assertEquals("listener-prevented", result.reason)
    }

    @Test
    fun tearingDownTheSubscriberStopsItHearingThePublisher() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val publisher = Publisher()
        val subscriber = Subscriber()
        registry.register(publisher)
        registry.register(subscriber)

        publisher.ping(1)
        registry.remove("subscriber")
        publisher.ping(2)

        assertEquals(listOf(1), subscriber.heard)
    }

    @Test
    fun aSlotsContributionsComeBackOrderedAndTheTakeoverIsFirst() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(ChromePlugin("late", priority = 0, slotOrder = 20))
        registry.register(ChromePlugin("early", priority = 0, slotOrder = 10))
        registry.register(ChromePlugin("owner", priority = 0, slotOrder = 99, takesOver = true))

        val bound = registry.contributions(ChromeSlot.Transport).map { it.pluginId }

        // The plugin taking the slot over sorts first, whatever its order —
        // that is where a chrome looks to decide whether to draw its default.
        assertEquals(listOf("owner", "early", "late"), bound)
    }

    @Test
    fun priorityBreaksAnOrderTieAndRegistrationOrderBreaksTheRest() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(ChromePlugin("first-registered", priority = 0, slotOrder = 10))
        registry.register(ChromePlugin("also-zero", priority = 0, slotOrder = 10))
        registry.register(ChromePlugin("important", priority = 5, slotOrder = 10))

        val bound = registry.contributions(ChromeSlot.Transport).map { it.pluginId }

        assertEquals(listOf("important", "first-registered", "also-zero"), bound)
    }

    @Test
    fun anEmptySlotIsEmptyRatherThanNull() = runTest {
        val host = FakePluginHost()
        val registry = registry(host, CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(ChromePlugin("transport-only", priority = 0, slotOrder = 0))

        assertTrue(registry.contributions(ChromeSlot.Background).isEmpty())
    }
}
