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
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

// A stop the player did not ask for.
//
// Audio focus going to a phone call, headphones coming out, an OS media key the
// engine handled itself, a source being swapped: every one of them stops playback
// without any controller here being called. The web player bridges each of them
// onto its own play/pause surface, and this did not — the context's field was
// written and nothing was told, so a chrome's button, the notification and every
// other device on the account all went on showing playing.
class EnginePlayStateBridgeTest {

    private suspend fun playing(): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()
        return player to backend
    }

    private fun ComposedPlayer.pauses(): MutableList<PlaySource> {
        val received: MutableList<PlaySource> = mutableListOf()
        on(CoreEvents.Pause) { received += it }
        return received
    }

    @Test
    fun anEnginePausingByItselfIsAnnounced() = runTest {
        val (player, backend) = playing()
        val pauses = player.pauses()

        backend.fire(CanonicalBackendEvent.PAUSE)

        assertEquals(1, pauses.size, "a pause nobody here asked for is still a pause")
        assertEquals(PlayState.PAUSED, player.playState())
    }

    @Test
    fun anEnginePauseWhileAlreadyPausedIsNotASecondEvent() = runTest {
        val (player, backend) = playing()
        player.pause()
        val pauses = player.pauses()

        backend.fire(CanonicalBackendEvent.PAUSE)

        // Engines repeat themselves, and a chrome that redraws on this would
        // flicker for nothing.
        assertEquals(0, pauses.size)
    }

    @Test
    fun theItemRunningOutIsNotAnnouncedAsAPause() = runTest {
        val (player, backend) = playing()
        val pauses = player.pauses()

        backend.fire(CanonicalBackendEvent.ENDED)
        backend.fire(CanonicalBackendEvent.PAUSE)

        // Every engine stops the clock when it reaches the end, so a pause always
        // rides along with the ending. Announcing it would have a chrome show a
        // paused player for the instant before it shows a finished one.
        assertEquals(0, pauses.size)
    }

    @Test
    fun anEnginePlayingByItselfIsAnnounced() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("a")))
        player.setup()
        val plays: MutableList<PlaySource> = mutableListOf()
        player.on(CoreEvents.Play) { plays += it }

        // Audio focus coming back, or a media key the engine took itself.
        backend.fire(CanonicalBackendEvent.PLAY)
        backend.fire(CanonicalBackendEvent.PLAY)

        assertEquals(1, plays.size, "announced when it starts, not on every repeat")
        assertEquals(PlayState.PLAYING, player.playState())
    }

    @Test
    fun aNewSourceStartingToLoadLeavesThePlayerPausedRatherThanClaimingToPlay() = runTest {
        val (player, backend) = playing()
        val pauses = player.pauses()

        backend.fire(CanonicalBackendEvent.LOAD_START)

        // Nothing is coming out while a new source loads. The state said playing,
        // so the next press of a toggle bound to it paused a silent player and the
        // press after that was the one that started the music.
        assertEquals(PlayState.PAUSED, player.playState())
        assertEquals(1, pauses.size)
    }
}
