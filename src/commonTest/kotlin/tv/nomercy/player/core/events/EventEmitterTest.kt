// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventEmitterTest {
    private val ping = EventKey<Int>("ping")

    @Test
    fun deliversTypedPayloadToTypedListener() {
        val bus = EventEmitter<Any>()
        var received: Int? = null
        bus.on(ping) { received = it }

        bus.emit(ping, 42)

        assertEquals(42, received)
    }

    @Test
    fun rawStringHatchAndFirehoseCarryAnyPayloads() {
        val bus = EventEmitter<Any>()
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
    fun listenersFireInRegistrationOrder() {
        val bus = EventEmitter<Any>()
        val order = mutableListOf<Int>()
        bus.on(ping) { order.add(1) }
        bus.on(ping) { order.add(2) }
        bus.on(ping) { order.add(3) }

        bus.emit(ping, 0)

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun registeringTheSameReferenceTwiceRegistersItOnce() {
        val bus = EventEmitter<Any>()
        var calls = 0
        val fn: (Int) -> Unit = { calls++ }
        bus.on(ping, fn)
        bus.on(ping, fn)

        bus.emit(ping, 1)

        assertEquals(1, calls)
        assertEquals(1, bus.listenerCount())
    }

    @Test
    fun offInsideAHandlerTakesEffectOnTheNextEmitNotTheCurrentOne() {
        val bus = EventEmitter<Any>()
        var calls = 0
        lateinit var subB: Subscription
        bus.on(ping) {
            calls++
            subB.dispose()
        }
        subB = bus.on(ping) { calls++ }

        bus.emit(ping, 1)
        assertEquals(2, calls)

        bus.emit(ping, 2)
        assertEquals(3, calls)
    }

    @Test
    fun oneThrowingListenerIsRoutedToOnListenerErrorAndTheRestStillRun() {
        val bus = EventEmitter<Any>()
        var goodRan = false
        var capturedName: String? = null
        bus.onListenerError = { name, _ -> capturedName = name }
        bus.on(ping) { throw RuntimeException("boom") }
        bus.on(ping) { goodRan = true }

        bus.emit(ping, 1)

        assertTrue(goodRan)
        assertEquals("ping", capturedName)
    }

    @Test
    fun throwingListenerIsSwallowedByDefaultWithNoErrorHookInstalled() {
        val bus = EventEmitter<Any>()
        var goodRan = false
        bus.on(ping) { throw RuntimeException("boom") }
        bus.on(ping) { goodRan = true }

        bus.emit(ping, 1)

        assertTrue(goodRan)
    }

    @Test
    fun offByReferenceRemovesRegistrationsMadeByBothOnAndOnce() {
        val bus = EventEmitter<Any>()
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
        val bus = EventEmitter<Any>()
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
        val bus = EventEmitter<Any>()
        var firstCalls = 0
        var secondCalls = 0
        val sub = bus.on(ping) { firstCalls++ }

        sub.dispose()
        bus.on(ping) { secondCalls++ }
        sub.dispose()
        bus.emit(ping, 1)

        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun listenerCountAndHasListenersTrackRegistrations() {
        val bus = EventEmitter<Any>()
        assertEquals(0, bus.listenerCount())
        val sub = bus.on(ping) {}
        assertEquals(1, bus.listenerCount())
        assertTrue(bus.hasListeners("ping"))
        sub.dispose()
        assertEquals(0, bus.listenerCount())
        assertFalse(bus.hasListeners("ping"))
        assertNull(bus.listenersOf("ping").firstOrNull())
    }
}
