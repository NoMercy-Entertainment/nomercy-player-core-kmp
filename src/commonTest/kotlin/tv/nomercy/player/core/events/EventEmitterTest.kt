// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventEmitterTest {
    private val ping = EventKey<Int>("ping")
    private lateinit var bus: EventEmitter<Any>

    @BeforeTest
    fun setUp() {
        bus = EventEmitter()
    }

    @Test
    fun rawStringHatchAndFirehoseCarryAnyPayloads() {
        var raw: Any? = null
        bus.on("plugin:lyrics:line") { raw = it }
        bus.emit("plugin:lyrics:line", "hello")
        assertEquals("hello", raw)

        val seen = mutableListOf<Pair<String, Any?>>()
        bus.onAll { name, data -> seen.add(name to data) }
        bus.emit(ping, 7)
        bus.emit("plugin:x:y", "z")

        assertEquals(listOf<Pair<String, Any?>>("ping" to 7, "plugin:x:y" to "z"), seen)
    }

    @Test
    fun listenersFireInRegistrationOrderAndDedupeByIdentity() {
        val order = mutableListOf<Int>()
        bus.on(ping) { order.add(1) }
        bus.on(ping) { order.add(2) }
        bus.on(ping) { order.add(3) }
        bus.emit(ping, 0)
        assertEquals(listOf(1, 2, 3), order)

        var calls = 0
        val fn: (Int) -> Unit = { calls++ }
        bus.on(ping, fn)
        bus.on(ping, fn)
        bus.emit(ping, 1)
        assertEquals(1, calls)
    }

    @Test
    fun offInsideAHandlerTakesEffectOnTheNextEmitNotTheCurrentOne() {
        var calls = 0
        lateinit var subB: Subscription
        bus.on(ping) { calls++; subB.dispose() }
        subB = bus.on(ping) { calls++ }

        bus.emit(ping, 1)
        assertEquals(2, calls)

        bus.emit(ping, 2)
        assertEquals(3, calls)
    }

    @Test
    fun throwingListenerIsRoutedToOnListenerErrorAndSwallowedByDefaultWithoutOne() {
        var goodRan = false
        bus.on(ping) { throw IllegalStateException("boom") }
        bus.on(ping) { goodRan = true }
        bus.emit(ping, 1)
        assertTrue(goodRan)

        var capturedName: String? = null
        val hooked = EventEmitter<Any>().apply { onListenerError = { name, _ -> capturedName = name } }
        hooked.on(ping) { throw IllegalStateException("boom") }
        hooked.emit(ping, 1)
        assertEquals("ping", capturedName)
    }

    @Test
    fun offByReferenceRemovesRegistrationsMadeByBothOnAndOnce() {
        var onCalls = 0
        var onceCalls = 0
        val onFn: (Int) -> Unit = { onCalls++ }
        val onceFn: (Int) -> Unit = { onceCalls++ }
        bus.on(ping, onFn)
        bus.once(ping, onceFn)
        bus.off(ping, onFn)
        bus.off(ping, onceFn)
        bus.emit(ping, 1)

        assertEquals(0, onCalls)
        assertEquals(0, onceCalls)
    }

    @Test
    fun onceFiresExactlyOnceAndItsSubscriptionReportsItselfDisposedAfterwards() {
        var calls = 0
        val sub = bus.once(ping) { calls++ }

        bus.emit(ping, 1)
        bus.emit(ping, 2)

        assertEquals(1, calls)
        assertEquals(0, bus.listenerCount())

        sub.dispose()
        bus.emit(ping, 3)
        assertEquals(1, calls)
    }

    @Test
    fun idempotentDisposalDoesNotDropAListenerRegisteredInBetween() {
        var firstCalls = 0
        var secondCalls = 0
        val sub = bus.on(ping) { firstCalls++ }

        sub.dispose()
        bus.on(ping) { secondCalls++ }
        sub.dispose(); bus.emit(ping, 1)

        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun hasListenersReflectsLiveRegistrations() {
        val sub = bus.on(ping) {}
        assertTrue(bus.hasListeners("ping"))
        sub.dispose(); assertFalse(bus.hasListeners("ping"))
    }
}
