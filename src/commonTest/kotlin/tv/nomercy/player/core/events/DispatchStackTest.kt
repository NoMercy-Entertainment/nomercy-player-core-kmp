// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val OUTER: EventKey<String> = EventKey("outer")
private val INNER: EventKey<String> = EventKey("inner")
private val BEFORE_SEEK: EventKey<BeforeEvent<Int>> = EventKey("beforeSeek")

// The dispatch stack.
//
// Every test here is really the same test: the stack must be empty again when
// the dispatch is over. A stack that leaks one entry does not fail loudly — it
// makes the next listener believe it is nested inside an event that finished,
// and a before-listener gating on that quietly starts refusing the wrong things.
class DispatchStackTest {

    private fun emitter(): EventEmitter<Unit> = EventEmitter()

    @Test
    fun nothingIsDispatchingWhenNothingIsHappening() {
        assertTrue(emitter().dispatching().isEmpty())
    }

    @Test
    fun aListenerSeesTheEventItIsInside() {
        val subject: EventEmitter<Unit> = emitter()
        var seen: List<String> = emptyList()
        subject.on(OUTER) { seen = subject.dispatching() }

        subject.emit(OUTER, "x")

        assertEquals(listOf("outer"), seen)
    }

    @Test
    fun aListenerThatEmitsSeesBothEventsOutermostFirst() {
        // The reason the stack is a list and not a single name: a listener
        // reacting to one event by emitting another is inside both.
        val subject: EventEmitter<Unit> = emitter()
        var seen: List<String> = emptyList()
        subject.on(OUTER) { subject.emit(INNER, "y") }
        subject.on(INNER) { seen = subject.dispatching() }

        subject.emit(OUTER, "x")

        assertEquals(listOf("outer", "inner"), seen)
        assertTrue(subject.dispatching().isEmpty(), "the stack outlived the dispatch")
    }

    @Test
    fun aThrowingErrorHandlerDoesNotStrandTheStack() {
        // A listener that throws is already caught. The one that is not is the
        // host's own error handler throwing while reporting that throw — the
        // exception leaves the dispatch from inside the catch block. Without an
        // unwind guard the event stays on the stack for the life of the player
        // and every later dispatch looks nested inside an event that ended.
        val subject: EventEmitter<Unit> = emitter()
        subject.onListenerError = { _, _ -> error("the host's reporter is broken too") }
        subject.on(OUTER) { error("a plugin threw") }

        assertFailsWith<IllegalStateException> { subject.emit(OUTER, "x") }

        assertTrue(subject.dispatching().isEmpty(), "a failed error report stranded the dispatch")
    }

    @Test
    fun aBeforeDispatchIsOnTheStackWhileItsListenersRun() = runTest {
        val subject: EventEmitter<Unit> = emitter()
        var seen: List<String> = emptyList()
        subject.on(BEFORE_SEEK) { seen = subject.dispatching() }

        subject.dispatchBefore(BEFORE_SEEK, 30)

        assertEquals(listOf("beforeSeek"), seen)
        assertTrue(subject.dispatching().isEmpty())
    }

    @Test
    fun aPreventedBeforeDispatchStillLeavesTheStackEmpty() = runTest {
        // dispatchBefore returns early when a listener refuses, and an early
        // return is exactly where a hand-placed pop gets missed.
        val subject: EventEmitter<Unit> = emitter()
        subject.on(BEFORE_SEEK) { it.preventDefault() }

        val result: BeforeDispatchResult<Int> = subject.dispatchBefore(BEFORE_SEEK, 30)

        assertTrue(result.prevented)
        assertTrue(subject.dispatching().isEmpty(), "a refused action left its event on the stack")
    }

    @Test
    fun theStackHandedOutCannotBeUsedToChangeTheRealOne() {
        val subject: EventEmitter<Unit> = emitter()
        var seen: List<String> = emptyList()
        subject.on(OUTER) { seen = subject.dispatching() }

        subject.emit(OUTER, "x")

        // The list a listener kept is a snapshot of that moment, not a live
        // view that empties under it after the dispatch ends.
        assertEquals(listOf("outer"), seen)
    }
}
