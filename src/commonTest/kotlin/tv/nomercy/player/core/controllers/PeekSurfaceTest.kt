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
import kotlin.test.assertNull

// Looking without moving.
//
// A chrome showing "up next" asks this on every frame it draws. If asking
// changed the queue, the queue would advance whenever the panel was open.
class PeekSurfaceTest {

    private suspend fun player(): ComposedPlayer {
        val subject = ComposedPlayer(backend = FakeMediaBackend())
        subject.setup()
        subject.queue(listOf(TestItem("a"), TestItem("b"), TestItem("c")))
        return subject
    }

    @Test
    fun peekingDoesNotAdvanceTheQueue() = runTest {
        val subject = player()
        subject.item("a")
        val before: Int = subject.index()

        assertEquals("b", subject.peekNext()?.id)
        assertEquals("b", subject.peekNext()?.id)
        assertEquals(before, subject.index(), "peeking moved the queue")
    }

    @Test
    fun thereIsNothingBeforeTheFirstItem() = runTest {
        val subject = player()
        subject.item("a")

        assertNull(subject.peekPrevious())
    }

    @Test
    fun seekToIndexIsOneBased() = runTest {
        // Zero would be an off-by-one that only shows up on the first item,
        // which is the one every session starts on.
        val subject = player()

        subject.seekToIndex(2)

        assertEquals("b", subject.item()?.id)
    }
}
