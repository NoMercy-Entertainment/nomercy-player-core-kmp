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

// When the player says the media is ready.
//
// The answer has to be "when the engine holds the source", not "when it has
// started playing", and the difference is not cosmetic: everything that
// continues a load waits on this — hiding a poster, restoring a saved position,
// reconciling to a server's position before starting. Waiting on a signal that
// only arrives once playback has begun is a deadlock in every one of those.
class MediaReadyTest {

    private class Rig(val player: ComposedPlayer, val backend: FakeMediaBackend) {
        val order: MutableList<String> = mutableListOf()
    }

    private suspend fun rig(): Rig {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        val rig = Rig(player, backend)

        player.on(CoreEvents.MediaReady) { rig.order += "ready" }
        player.on(CoreEvents.Playing) { rig.order += "playing" }

        player.queue(listOf(TestItem("a"), TestItem("b")))
        player.setup()
        return rig
    }

    @Test
    fun aLoadReportsReadyWithoutBeingPlayed() = runTest {
        val rig: Rig = rig()

        rig.player.load(TestItem("a"))

        assertEquals(listOf("ready"), rig.order)
    }

    @Test
    fun startingPlaybackReportsReadyAfterTheEngineIsRunning() = runTest {
        // The other order, and it is not a contradiction. Playing an empty
        // player loads on the way past, and in the reference the engine is
        // kicked while that load is still settling — so readiness lands last.
        // A recorded scenario pins it, and a chrome taking its poster down on
        // this would otherwise uncover a black frame.
        val rig: Rig = rig()

        rig.player.play()

        assertEquals(listOf("playing", "ready"), rig.order)
    }

    @Test
    fun playingSomethingAlreadyLoadedDoesNotReportReadyTwice() = runTest {
        // Pause and play again, and nothing was loaded. A second announcement
        // there would restart every continuation waiting on one — for Connect
        // that is a re-seek to a stale server position on every resume.
        val rig: Rig = rig()
        rig.player.load(TestItem("a"))
        rig.order.clear()

        rig.player.play()

        assertTrue(rig.order.none { it == "ready" }, "the order was ${rig.order}")
    }

    @Test
    fun everyLoadReportsItsOwnReady() = runTest {
        // Once per load, not once per player. A second track that never
        // announced itself leaves anything waiting on the continuation stuck on
        // the first one — which on a queue is every track after the first.
        val rig: Rig = rig()

        rig.player.load(TestItem("a"))
        rig.player.load(TestItem("b"))

        assertEquals(listOf("ready", "ready"), rig.order)
    }

    @Test
    fun aPlayerWithNoEngineStillReportsReady() = runTest {
        // A null backend is a player that has not been given an engine yet. The
        // load is a no-op, and reporting nothing would leave a caller waiting
        // forever instead of continuing against an engine that plays silence.
        val player = ComposedPlayer(backend = null)
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.MediaReady) { seen += "ready" }
        player.setup()

        player.load(TestItem("a"))

        assertEquals(listOf("ready"), seen)
    }
}
