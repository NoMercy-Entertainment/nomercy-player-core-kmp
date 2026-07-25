// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchBeforeTest {
    private val beforePlay = EventKey<BeforeEvent<Int>>("beforePlay")

    @Test
    fun withNoListenersTheActionProceeds() = runTest {
        val bus = EventEmitter<Any>()

        val result = bus.dispatchBefore(beforePlay, 5)

        assertFalse(result.prevented)
        assertEquals(5, result.data)
        assertNull(result.reason)
    }

    @Test
    fun preventDefaultRefusesTheActionWithTheListenerPreventedReason() = runTest {
        val bus = EventEmitter<Any>()
        bus.on(beforePlay) { it.preventDefault() }

        val result = bus.dispatchBefore(beforePlay, 5)

        assertTrue(result.prevented)
        assertEquals(PreventReason.ListenerPrevented, result.reason)
    }

    @Test
    fun aListenerMutationIsReadBackFromTheResult() = runTest {
        val bus = EventEmitter<Any>()
        bus.on(beforePlay) { it.data = 99 }

        val result = bus.dispatchBefore(beforePlay, 5)

        assertFalse(result.prevented)
        assertEquals(99, result.data)
    }

    @Test
    fun listenersRunInOrderAndStopImmediatePropagationEndsTheLoop() = runTest {
        val bus = EventEmitter<Any>()
        val order = mutableListOf<Int>()
        bus.on(beforePlay) { order.add(1) }
        bus.on(beforePlay) { it.stopImmediatePropagation(); order.add(2) }
        bus.on(beforePlay) { order.add(3) }

        bus.dispatchBefore(beforePlay, 5)

        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun aDelayGateIsAwaitedToCompletionBeforeTheResultIsReturned() = runTest {
        val bus = EventEmitter<Any>()
        var gateFinished = false
        bus.on(beforePlay) { event -> event.delay { delay(100); gateFinished = true } }

        val result = bus.dispatchBefore(beforePlay, 5)

        assertTrue(gateFinished)
        assertFalse(result.prevented)
    }

    @Test
    fun gatesFromDifferentListenersRunConcurrentlyAndAllMustComplete() = runTest {
        val bus = EventEmitter<Any>()
        val finished = mutableListOf<String>()
        bus.on(beforePlay) { event -> event.delay { delay(300); finished.add("slow") } }
        bus.on(beforePlay) { event -> event.delay { delay(100); finished.add("fast") } }

        val result = bus.dispatchBefore(beforePlay, 5)

        // Concurrent, not sequential: the shorter gate finishes first even though
        // it was registered second. Both are awaited before the result lands.
        assertEquals(listOf("fast", "slow"), finished)
        assertFalse(result.prevented)
    }

    @Test
    fun aGateThatOutlastsTheTimeoutRefusesTheActionWithDelayTimeout() = runTest {
        val bus = EventEmitter<Any>()
        bus.on(beforePlay) { event -> event.delay { delay(20_000) } }

        val result = bus.dispatchBefore(beforePlay, 5, timeoutMs = 10_000)

        assertTrue(result.prevented)
        assertEquals(PreventReason.DelayTimeout, result.reason)
    }

    @Test
    fun aFailingGateRefusesTheActionAndDoesNotCancelItsSiblings() = runTest {
        val bus = EventEmitter<Any>()
        var siblingFinished = false
        bus.on(beforePlay) { event -> event.delay { throw IllegalStateException("nope") } }
        bus.on(beforePlay) { event -> event.delay { delay(100); siblingFinished = true } }

        val result = bus.dispatchBefore(beforePlay, 5)

        assertTrue(result.prevented)
        assertEquals(PreventReason.DelayRejected, result.reason)
        // allSettled semantics: one plugin's failing gate must not take another
        // plugin's cleanup down with it.
        assertTrue(siblingFinished)
    }

    @Test
    fun aGateFailureOutranksPreventDefaultAsTheReportedReason() = runTest {
        val bus = EventEmitter<Any>()
        bus.on(beforePlay) { event ->
            event.preventDefault()
            event.delay { throw IllegalStateException("nope") }
        }

        val result = bus.dispatchBefore(beforePlay, 5)

        // Both refuse the action, so `prevented` is not in question. The reason
        // is: the gate is the more specific answer to why it did not happen.
        assertEquals(PreventReason.DelayRejected, result.reason)
    }

    @Test
    fun aThrowingListenerIsIsolatedAndTheRemainingListenersStillRun() = runTest {
        val bus = EventEmitter<Any>()
        var secondRan = false
        var capturedName: String? = null
        bus.onListenerError = { name, _ -> capturedName = name }
        bus.on(beforePlay) { throw IllegalStateException("boom") }
        bus.on(beforePlay) { secondRan = true }

        val result = bus.dispatchBefore(beforePlay, 5)

        assertTrue(secondRan)
        assertFalse(result.prevented)
        assertEquals("beforePlay", capturedName)
    }

    @Test
    fun aBeforeListenerRegisteredWithOnceFiresOnlyOnce() = runTest {
        val bus = EventEmitter<Any>()
        var calls = 0
        bus.once(beforePlay) { calls++ }

        bus.dispatchBefore(beforePlay, 5)
        bus.dispatchBefore(beforePlay, 5)

        assertEquals(1, calls)
    }

    @Test
    fun theFirehoseCannotSeeTheBeforeSeamSoObserversMustSubscribeByName() = runTest {
        val bus = EventEmitter<Any>()
        val firehoseSaw = mutableListOf<String>()
        val byName = mutableListOf<String>()
        bus.onAll { name, _ -> firehoseSaw.add(name) }
        bus.on(beforePlay) { byName.add("beforePlay") }

        bus.dispatchBefore(beforePlay, 5)

        // before* listeners are invoked directly rather than through emit(), so
        // stopImmediatePropagation can cut the loop. The firehose is fed by
        // emit() alone and therefore never sees them. Every conformance runner
        // has to subscribe by name to observe the cancellable seam; the web
        // trio has the same shape, and a harness that captured only through the
        // firehose would silently assert nothing about any before* event.
        assertEquals(emptyList(), firehoseSaw)
        assertEquals(listOf("beforePlay"), byName)
    }
}
