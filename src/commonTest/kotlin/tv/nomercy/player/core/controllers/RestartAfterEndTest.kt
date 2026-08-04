// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Restarting an episode that finished.
 *
 * Filed three times: "restart episode in the native player does not actually
 * start the episode after seeking back to 0."
 *
 * The seek moved the playhead and left the phase at ENDED, so the player went
 * on believing it was finished — the chrome kept its replay state and a play()
 * arriving after the seek found a player at its own end.
 *
 * Graded through the player rather than through seekingTransition, because the
 * transition helper was never wrong on its own terms: it restored the phase it
 * was given, and ENDED was simply not in the set it restored.
 */
class RestartAfterEndTest {

    @Test
    fun seekingOffTheEndLeavesTheEnd() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.setup(PlayerConfig())
        player.load(TestItem(id = "ep", title = "Episode", url = "https://s.test/ep.m3u8"))

        backend.fire(CanonicalBackendEvent.ENDED)
        assertEquals(PlayerPhase.ENDED, player.phase())

        player.time(0.0)

        assertEquals(PlayerPhase.PAUSED, player.phase(), "an episode scrubbed back to zero is not finished")
        assertEquals(0.0, player.time())
    }

    // A seek that is not off the end keeps the phase it had, or this would make
    // every scrub during playback announce a pause.
    @Test
    fun anOrdinarySeekKeepsThePhaseItHad() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.setup(PlayerConfig())
        player.load(TestItem(id = "ep", title = "Episode", url = "https://s.test/ep.m3u8"))

        backend.fire(CanonicalBackendEvent.PLAYING)
        val before: PlayerPhase = player.phase()

        player.time(42.0)

        assertEquals(before, player.phase())
    }
}
