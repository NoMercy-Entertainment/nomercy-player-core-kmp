// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.events.BackendErrorPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun anEngineFailureReachesTheOneSurfaceAConsumerListensOn() = runTest {
        val (player, backend) = playing()
        val failures: MutableList<PlayerErrorEvent> = mutableListOf()
        val raw: MutableList<BackendErrorPayload> = mutableListOf()
        player.on(CoreEvents.Error) { failures += it }
        player.on(CoreEvents.BackendError) { raw += it }

        // The refusal three engines emit when HDR content meets a screen that
        // cannot show it and nothing can convert it. It exists to stop playback
        // and say why, and it was announced into nothing: the canonical error was
        // the one name in the vocabulary with no handler, from nine emit sites.
        backend.fire(CanonicalBackendEvent.ERROR, CoreErrorCodes.HDR_UNPLAYABLE)

        assertEquals(CoreErrorCodes.HDR_UNPLAYABLE, failures.single().code)
        assertEquals(CoreErrorCodes.HDR_UNPLAYABLE, raw.single().error)
        assertEquals("FakeMediaBackend", raw.single().kind, "which engine failed is worth a support log")
    }

    @Test
    fun anEngineFailureWithNoCodeIsNotDressedUpAsOne() = runTest {
        val (player, backend) = playing()
        val failures: MutableList<PlayerErrorEvent> = mutableListOf()
        player.on(CoreEvents.Error) { failures += it }

        // AVFoundation and libVLC report a failure without one. Media3 reports its
        // own name for it, which is not a code in this scheme either — treating
        // either as one would put a code in a dashboard that no dashboard knows.
        backend.fire(CanonicalBackendEvent.ERROR, "ERROR_CODE_DECODING_FAILED")

        // The first one is not the answer. A decode failure buys one reload, as
        // the web's `recoverMediaError` does, and a viewer never reads about the
        // ones that recovery fixes.
        assertTrue(failures.isEmpty(), "a first decode failure is retried, not announced")

        backend.fire(CanonicalBackendEvent.ERROR, "ERROR_CODE_DECODING_FAILED")

        val reported: PlayerErrorEvent = failures.single()
        assertEquals(
            "media/decode-fatal-all",
            reported.code,
            "the web's default for an element error it cannot classify",
        )
        assertTrue(
            reported.context.values.any { it == "ERROR_CODE_DECODING_FAILED" },
            "the engine's own word for it is kept, as context rather than as the code",
        )
    }

    @Test
    fun aFailureTakesTheSpinnerDownWithIt() = runTest {
        val (player, backend) = playing()
        backend.fire(CanonicalBackendEvent.WAITING)
        assertEquals(BufferState.STALLED, player.bufferState())

        backend.fire(CanonicalBackendEvent.ERROR)

        // The reference derives this from the engine's state, and an engine in
        // error is not an engine that is buffering. Stored rather than derived
        // here, so it stayed STALLED and left a spinner turning on top of an item
        // that had already failed.
        assertEquals(BufferState.IDLE, player.bufferState())
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
