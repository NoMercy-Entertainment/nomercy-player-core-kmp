// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.BeforeMutationPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Moving the cursor announces itself, and can be refused.
 *
 * The reference guards exactly one queue method and guards it twice — `current`,
 * from `item(target)` and from `seekToIndex` (queue.ts:292 and :384). Natively
 * the guard was wired to `recordMetric`, `repeatState` and `shuffleState` and to
 * nothing in the queue at all, so an advisory plugin refusing a track change had
 * nothing to refuse and `beforeMutation` never fired on the path a viewer
 * actually takes.
 *
 * `recordMetric` is worth noticing on the way past: it is in `HOT_MUTATIONS`, so
 * that call site could never fire either. Two of the three native guards were on
 * methods the reference does not guard, and the one it does guard had none.
 */
class CursorMutationIsGuardedTest {

    @Test
    fun choosingAnItemAnnouncesTheMutation() = runTest {
        val player = newPlayer()
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.BeforeMutation) { seen += (it.data as BeforeMutationPayload).method }

        player.item("b")

        assertEquals(listOf("current"), seen)
    }

    @Test
    fun jumpingToAnIndexAnnouncesTheMutation() = runTest {
        val player = newPlayer()
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.BeforeMutation) { seen += (it.data as BeforeMutationPayload).method }

        player.seekToIndex(2)

        assertEquals(listOf("current"), seen)
    }

    @Test
    fun aRefusedCursorMoveLeavesTheQueueWhereItWas() = runTest {
        val player = newPlayer()
        player.on(CoreEvents.BeforeMutation) { it.preventDefault() }

        player.seekToIndex(2)

        assertEquals(0, player.index())
    }

    private fun newPlayer(): ComposedPlayer = ComposedPlayer(backend = FakeMediaBackend()).also {
        it.queue(listOf(TestItem("a"), TestItem("b"), TestItem("c")))
    }
}
