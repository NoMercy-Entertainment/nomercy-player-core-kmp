// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The queue, changed through the player rather than through a property that
// happens to be public.
//
// These operations have existed on the controller since it was written and
// stopped there, so a consumer holding a player could read the queue and not
// change it. The tests are on the player for that reason: the controller's own
// behaviour is already covered, and what was missing was the reach.
class PlayerQueueSurfaceTest {

    private suspend fun player(): ComposedPlayer {
        val subject = ComposedPlayer(backend = FakeMediaBackend())
        subject.setup()
        subject.queue(listOf(TestItem("a"), TestItem("b")))
        return subject
    }

    @Test
    fun itemsCanBeAddedAtEitherEnd() = runTest {
        val subject = player()

        subject.queueAppend(listOf(TestItem("c")))
        subject.queuePrepend(listOf(TestItem("z")))

        assertEquals(listOf("z", "a", "b", "c"), subject.queue().map { it.id })
        assertEquals(4, subject.queueLength())
    }

    @Test
    fun anItemCanBeFoundAndRemovedByIdRatherThanByPosition() = runTest {
        // Position is what a reorder invalidates. Removing "the second one"
        // after a shuffle removes something else.
        val subject = player()

        assertEquals(1, subject.queueIndexOf("b"))
        subject.queueRemove("a")

        assertEquals(listOf("b"), subject.queue().map { it.id })
    }

    @Test
    fun anItemCanBeMovedWithoutLosingTheRest() = runTest {
        val subject = player()
        subject.queueAppend(listOf(TestItem("c")))

        subject.queueMove(0, 2)

        assertEquals(listOf("b", "c", "a"), subject.queue().map { it.id })
    }

    @Test
    fun insertingPutsItemsWhereTheyWereAsked() = runTest {
        val subject = player()

        subject.queueInsert(listOf(TestItem("x")), 1)

        assertEquals(listOf("a", "x", "b"), subject.queue().map { it.id })
    }

    @Test
    fun sortingUsesTheComparatorItWasGiven() = runTest {
        val subject = player()
        subject.queueAppend(listOf(TestItem("c")))

        subject.queueSort(compareByDescending { it.id })

        assertEquals(listOf("c", "b", "a"), subject.queue().map { it.id })
    }

    @Test
    fun clearingLeavesNothingAndSaysSo() = runTest {
        val subject = player()

        subject.queueClear()

        assertEquals(0, subject.queueLength())
        assertEquals(emptyList(), subject.queue())
    }

    @Test
    fun shufflingKeepsEveryItem() = runTest {
        // A shuffle that drops or duplicates an item is the kind of bug a
        // listener notices only three tracks later.
        val subject = player()
        subject.queueAppend(listOf(TestItem("c"), TestItem("d")))

        subject.queueShuffle()

        assertEquals(setOf("a", "b", "c", "d"), subject.queue().map { it.id }.toSet())
        assertEquals(4, subject.queueLength())
    }
}
