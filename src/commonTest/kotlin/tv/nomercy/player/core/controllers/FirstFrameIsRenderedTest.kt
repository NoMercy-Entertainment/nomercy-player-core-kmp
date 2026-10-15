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
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

// FirstFrame is a picture on screen, not the engine saying it is playing.
class FirstFrameIsRenderedTest {

    private class FrameSignalBackend : FakeMediaBackend() {
        override val reportsRenderedFirstFrame: Boolean = true
    }

    private suspend fun started(backend: FakeMediaBackend): Int {
        val player = ComposedPlayer(backend = backend)
        var count = 0
        player.on(CoreEvents.FirstFrame) { count += 1 }
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()
        backend.fire(CanonicalBackendEvent.PLAYING)
        return count
    }

    @Test
    fun playingAloneIsNotAFirstFrameForAnEngineThatReportsFrames() = runTest {
        val backend = FrameSignalBackend()
        val player = ComposedPlayer(backend = backend)
        var count = 0
        player.on(CoreEvents.FirstFrame) { count += 1 }
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()

        backend.fire(CanonicalBackendEvent.PLAYING)
        assertEquals(0, count, "FirstFrame fired on PLAYING, before any frame was drawn")

        backend.fire(CanonicalBackendEvent.FIRST_FRAME_RENDERED)
        assertEquals(1, count)

        backend.fire(CanonicalBackendEvent.FIRST_FRAME_RENDERED)
        assertEquals(1, count, "a second frame of the same item announced again")
    }

    @Test
    fun aNewItemGetsItsOwnFirstFrame() = runTest {
        val backend = FrameSignalBackend()
        val player = ComposedPlayer(backend = backend)
        var count = 0
        player.on(CoreEvents.FirstFrame) { count += 1 }
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()

        backend.fire(CanonicalBackendEvent.FIRST_FRAME_RENDERED)
        backend.fire(CanonicalBackendEvent.LOAD_START)
        backend.fire(CanonicalBackendEvent.FIRST_FRAME_RENDERED)

        assertEquals(2, count)
    }

    @Test
    fun anEngineWithNoFrameSignalStillGetsFirstFrameOnPlaying() = runTest {
        assertEquals(1, started(FakeMediaBackend()))
    }
}
