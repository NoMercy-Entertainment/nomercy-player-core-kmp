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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// advanceTimeBy/runCurrent drive the virtual clock these tests measure
// cancellation against.
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleRegistryTest {

    @Test
    fun cleanupsRunNewestFirstLikeAStackUnwind() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val order = mutableListOf<Int>()
        registry.addCleanup { order.add(1) }
        registry.addCleanup { order.add(2) }
        registry.addCleanup { order.add(3) }

        registry.dispose()

        assertEquals(listOf(3, 2, 1), order)
    }

    @Test
    fun aScheduledCallbackDoesNotFireAfterDispose() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var fired = false
        registry.timeout(1_000) { fired = true }

        registry.dispose()
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(fired)
    }

    @Test
    fun aScheduledCallbackFiresWhenTheRegistryIsStillAlive() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var fired = false
        registry.timeout(1_000) { fired = true }

        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(fired)
        registry.dispose()
    }

    @Test
    fun anIntervalStopsAtDisposeRatherThanRunningForever() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var ticks = 0
        registry.interval(100) { ticks++ }

        advanceTimeBy(350)
        runCurrent()
        val beforeDispose = ticks

        registry.dispose()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(3, beforeDispose)
        assertEquals(beforeDispose, ticks)
    }

    @Test
    fun aCollectorStopsAtDispose() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val seen = mutableListOf<Int>()
        registry.listen(source) { seen.add(it) }
        runCurrent()

        source.emit(1)
        runCurrent()
        registry.dispose()
        source.emit(2)
        runCurrent()

        assertEquals(listOf(1), seen)
    }

    @Test
    fun launchingAfterDisposeGivesBackAJobThatIsAlreadyCancelled() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.dispose()
        var ran = false

        val job = registry.launch { ran = true }
        runCurrent()

        assertTrue(job.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun disposeIsIdempotentAndLateCleanupRunsImmediately() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var count = 0

        registry.dispose()
        registry.dispose()
        registry.addCleanup { count++ }

        assertTrue(registry.isDisposed())
        assertEquals(1, count)
    }

    @Test
    fun aThrowingCleanupIsReportedAndTheRestStillRun() = runTest {
        val registry = LifecycleRegistry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val ran = mutableListOf<String>()
        var reported: Throwable? = null
        registry.onCleanupError = { reported = it }
        registry.addCleanup { ran.add("first") }
        registry.addCleanup { throw IllegalStateException("boom") }
        registry.addCleanup { ran.add("third") }

        registry.dispose()

        // Newest-first, so "third" runs, then the thrower, then "first". One
        // plugin's broken teardown must not strand the cleanups under it.
        assertEquals(listOf("third", "first"), ran)
        assertEquals("boom", reported?.message)
    }
}
