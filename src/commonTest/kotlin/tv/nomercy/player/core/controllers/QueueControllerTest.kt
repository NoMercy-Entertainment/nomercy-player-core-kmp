// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueControllerTest {

    @Test
    fun settingTheQueueStartsAtTheFirstItemAndAnnouncesBoth() = runTest {
        val rig = Rig()
        val queues = EventLog().capture(rig.ctx, CoreEvents.Queue)
        val current = EventLog().capture(rig.ctx, CoreEvents.Item)

        rig.queue.queue(items("a", "b", "c"))

        assertEquals("a", rig.queue.item()?.id)
        assertEquals(0, rig.queue.index())
        assertEquals(3, queues.last().items.size)
        assertEquals("a", current.last().item?.id)
    }

    @Test
    fun wiringTheQueueTwiceDoesNotDoubleItsEvents() = runTest {
        val rig = Rig()
        val queues = EventLog().capture(rig.ctx, CoreEvents.Queue)

        rig.queue.wireQueue()
        rig.queue.queue(items("a"))

        assertEquals(1, queues.size)
    }

    @Test
    fun appendingSaysWhereTheNewItemsStart() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a"))
        val appended = EventLog().capture(rig.ctx, CoreEvents.QueueAppend)

        rig.queue.queueAppend(items("b", "c"))

        assertEquals(1, appended.single().from)
        assertEquals(3, rig.queue.queueLength())
    }

    @Test
    fun prependingKeepsThePlayingItemPlaying() = runTest {
        val rig = Rig()
        rig.queue.queue(items("b"))
        val prepended = EventLog().capture(rig.ctx, CoreEvents.QueuePrepend)

        rig.queue.queuePrepend(items("a"))

        assertEquals(1, prepended.size)
        assertEquals(listOf("a", "b"), rig.queue.queue().map { it.id })
        assertEquals("b", rig.queue.item()?.id)
    }

    @Test
    fun removingReportsWhatWentAndFromWhere() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b", "c"))
        val removed = EventLog().capture(rig.ctx, CoreEvents.QueueRemove)

        rig.queue.queueRemoveAt(1)

        assertEquals("b", removed.single().id)
        assertEquals(1, removed.single().index)
        assertEquals(listOf("a", "c"), rig.queue.queue().map { it.id })
    }

    @Test
    fun shufflingAndSortingBothAnnounceThemselves() = runTest {
        val rig = Rig()
        rig.queue.queue(items("c", "a", "b", "d"))
        val shuffles = EventLog().capture(rig.ctx, CoreEvents.QueueShuffle)
        val sorts = EventLog().capture(rig.ctx, CoreEvents.QueueSort)

        rig.queue.queueShuffle()
        rig.queue.queueSort(compareBy { it.id })

        assertEquals(1, shuffles.size)
        assertEquals(1, sorts.size)
        assertEquals(listOf("a", "b", "c", "d"), rig.queue.queue().map { it.id })
    }

    @Test
    fun lookupAndPeekingDoNotMoveTheCursor() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b", "c"))
        rig.queue.item("b")

        assertEquals(2, rig.queue.queueIndexOf("c"))
        assertEquals(-1, rig.queue.queueIndexOf("nope"))
        assertEquals("c", rig.queue.peekNext()?.id)
        assertEquals("a", rig.queue.peekPrevious()?.id)
        assertEquals(1, rig.queue.index())
    }

    @Test
    fun jumpingToAPositionCountsFromOne() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b", "c"))

        rig.queue.seekToIndex(3)

        assertEquals(2, rig.queue.index())
        assertEquals("c", rig.queue.item()?.id)
    }

    @Test
    fun jumpingPastTheEndDoesNothingRatherThanPlayingTheLastTrack() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b", "c"))
        rig.queue.seekToIndex(1)

        rig.queue.seekToIndex(99)

        assertEquals(0, rig.queue.index())
    }

    @Test
    fun jumpingToZeroIsARejectedCallNotASilentOffByOne() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b"))

        assertFailsWith<IllegalArgumentException> { rig.queue.seekToIndex(0) }
    }

    @Test
    fun theBacklogHasItsOwnEventsSeparateFromTheQueue() = runTest {
        val rig = Rig()
        val backlogAppends = EventLog().capture(rig.ctx, CoreEvents.BacklogAppend)
        val queueAppends = EventLog().capture(rig.ctx, CoreEvents.QueueAppend)

        rig.queue.backlogAppend(items("a"))

        assertEquals(listOf("a"), rig.queue.backlog().map { it.id })
        assertEquals(1, backlogAppends.size)
        assertEquals(0, queueAppends.size)
    }

    @Test
    fun choosingAnItemBeforeSetupMovesTheCursorWithoutLoading() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b"))

        rig.queue.item("b")

        // Building a queue ahead of time must not start playback.
        assertEquals("b", rig.queue.item()?.id)
        assertEquals(0, rig.backend.loadedUrls.size)
    }

    @Test
    fun playingAnItemLoadsItThenStartsIt() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))

        rig.queue.playItem("b")

        assertEquals("b", rig.queue.item()?.id)
        assertEquals(listOf("https://example.test/b"), rig.backend.loadedUrls)
        assertEquals(1, rig.backend.playCount)
    }

    @Test
    fun choosingAnItemLoadsItWithoutStartingItUnlessAsked() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))

        rig.queue.item("b")

        assertEquals(listOf("https://example.test/b"), rig.backend.loadedUrls)
        assertEquals(0, rig.backend.playCount)
    }

    @Test
    fun choosingAnItemThatIsNotThereChangesNothing() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))

        rig.queue.item("nope")

        assertEquals("a", rig.queue.item()?.id)
        assertEquals(0, rig.backend.loadedUrls.size)
    }

    @Test
    fun clearingTheQueueLeavesNothingCurrent() = runTest {
        val rig = Rig()
        rig.queue.queue(items("a", "b"))
        val cleared = EventLog().capture(rig.ctx, CoreEvents.QueueClear)

        rig.queue.queueClear()

        assertEquals(2, cleared.single().previousLength)
        assertNull(rig.queue.item())
        assertTrue(rig.queue.queue().isEmpty())
    }
}
