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
import kotlin.test.assertTrue

// The backlog through the player.
//
// Every assertion here is about the backlog NOT being a second queue. It shares
// a data structure with the queue, and the risk in sharing one is that the
// behaviour leaks with it: an append that moves the cursor, a clear that stops
// playback, a history that quietly becomes the thing that plays next.
class BacklogSurfaceTest {

    private fun playerWithQueue(): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend()).also {
            it.queue(listOf(TestItem("a"), TestItem("b"), TestItem("c")))
        }

    @Test
    fun theBacklogStartsEmptyRatherThanMirroringTheQueue() {
        assertEquals(emptyList(), playerWithQueue().backlog())
    }

    @Test
    fun appendingToTheBacklogLeavesTheQueueAndCursorAlone() = runTest {
        val player: ComposedPlayer = playerWithQueue()
        // One-based: track 2 is index 1.
        player.seekToIndex(2)

        player.backlogAppend(listOf(TestItem("old")))

        assertEquals(1, player.index(), "the cursor moved when history was recorded")
        assertEquals(listOf("a", "b", "c"), player.queue().map { it.id })
        assertEquals(listOf("old"), player.backlog().map { it.id })
    }

    @Test
    fun clearingTheBacklogLeavesTheQueueIntact() {
        val player: ComposedPlayer = playerWithQueue()
        player.backlogAppend(listOf(TestItem("old")))

        player.backlogClear()

        assertTrue(player.backlog().isEmpty())
        assertEquals(3, player.queueLength(), "clearing history emptied the queue")
    }

    @Test
    fun replacingTheBacklogRestoresAPersistedHistoryInOneEvent() {
        // A host resuming a session hands back what it stored. Doing that as an
        // append per item would emit a change per item, and a consumer
        // re-rendering a history list would do it once per entry.
        val player: ComposedPlayer = playerWithQueue()
        val changes: MutableList<Int> = mutableListOf()
        player.on(CoreEvents.Backlog) { changes += it.items.size }

        player.backlog(listOf(TestItem("x"), TestItem("y"), TestItem("z")))

        assertEquals(listOf("x", "y", "z"), player.backlog().map { it.id })
        assertEquals(listOf(3), changes, "restoring a history emitted more than one change")
    }

    @Test
    fun removingFromTheBacklogTakesTheNamedItemAndNothingElse() {
        val player: ComposedPlayer = playerWithQueue()
        player.backlog(listOf(TestItem("x"), TestItem("y"), TestItem("z")))

        player.backlogRemove("y")

        assertEquals(listOf("x", "z"), player.backlog().map { it.id })
    }

    @Test
    fun removingSomethingTheBacklogNeverHadIsNotAnError() {
        // A host that forgets what it stored should not crash the player.
        val player: ComposedPlayer = playerWithQueue()
        player.backlog(listOf(TestItem("x")))

        player.backlogRemove("nothing-like-this")

        assertEquals(listOf("x"), player.backlog().map { it.id })
    }

    @Test
    fun theBacklogIsNotWhatPlaysNext() {
        // The one that would hurt: history in front of the cursor. A player that
        // treated the backlog as a source of upcoming items would replay what
        // the viewer already watched instead of continuing.
        val player: ComposedPlayer = playerWithQueue()
        player.backlog(listOf(TestItem("watched-yesterday")))

        assertEquals("b", player.peekNext()?.id)
    }
}
