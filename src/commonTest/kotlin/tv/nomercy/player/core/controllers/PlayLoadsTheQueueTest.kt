// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// queue(items) then play() has to play something.
//
// It did not: play() called the engine with nothing loaded, so the viewer got
// silence and a running clock. That is the hardest kind of bug to report and
// the easiest to miss in a test that only counts playCount.
class PlayLoadsTheQueueTest {

    private fun rig(): Rig = Rig().ready()

    @Test
    fun playingAnUntouchedQueuePlaysItsFirstItem() = runTest {
        val rig = rig()
        rig.queue.queue(items("a", "b"))

        rig.transport.play()

        assertEquals(listOf("https://example.test/a"), rig.backend.loadedUrls)
        assertEquals(1, rig.backend.playCount)
        assertEquals(0, rig.queue.index())
        assertEquals("a", rig.queue.item()?.id)
    }

    @Test
    fun playingAgainDoesNotReloadWhatIsAlreadyPlaying() = runTest {
        val rig = rig()
        rig.queue.queue(items("a", "b"))

        rig.transport.play()
        rig.transport.pause()
        rig.transport.play()

        // Reloading would restart the item from zero, which is not what resume
        // means.
        assertEquals(1, rig.backend.loadedUrls.size)
        assertEquals(2, rig.backend.playCount)
    }

    @Test
    fun playingAnEmptyQueueStillDrivesTheEngineWithoutLoadingAnything() = runTest {
        val rig = rig()

        rig.transport.play()

        // A consumer driving an engine that already has media — a live stream
        // handed in directly — has no queue and must still be able to play.
        assertTrue(rig.backend.loadedUrls.isEmpty())
        assertEquals(1, rig.backend.playCount)
        assertEquals(PlayerPhase.STARTING, rig.ctx.phase)
    }

    @Test
    fun anExplicitItemChoiceIsNotOverriddenByTheFallback() = runTest {
        val rig = rig()
        rig.queue.queue(items("a", "b"))
        rig.queue.item("b")

        rig.transport.play()

        assertEquals(listOf("https://example.test/b"), rig.backend.loadedUrls)
        assertEquals("b", rig.queue.item()?.id)
    }
}
