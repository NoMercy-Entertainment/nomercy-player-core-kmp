// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.MediaFormatError
import tv.nomercy.player.core.errors.StateError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tv.nomercy.player.testing.TestItem

private class SigningAuth : AuthController() {
    override fun transformUrl(url: String): String = "$url?token=abc"
}

class PlayerContextTest {

    @Test
    fun aPhaseChangeReportsBothEndsOfTheMove() {
        val ctx = newContext()
        val log = EventLog()
        log.record(ctx, CoreEvents.Phase) { "${it.from}->${it.to}" }

        ctx.transitionPhase(PlayerPhase.READY)

        assertEquals(PlayerPhase.READY, ctx.phase)
        assertEquals(listOf("IDLE->READY"), log.names)
    }

    @Test
    fun transitioningToThePhaseItIsAlreadyInSaysNothing() {
        val ctx = newContext()
        val log = EventLog()
        log.record(ctx, CoreEvents.Phase) { "fired" }

        ctx.transitionPhase(PlayerPhase.IDLE)

        // A chrome that redraws on every phase event must not redraw for a
        // change that did not happen.
        assertEquals(emptyList(), log.names)
    }

    @Test
    fun aTransportCallBeforeSetupSaysItIsNotReady() {
        val ctx = newContext()

        val failure = assertFailsWith<PlayerError> { ctx.assertReady() }

        assertEquals("core:player/not-ready", failure.code)
    }

    @Test
    fun aTransportCallAfterDisposeSaysSoInsteadOfSayingNotReady() {
        val ctx = newContext()
        ctx.transitionPhase(PlayerPhase.DISPOSED)

        val failure = assertFailsWith<PlayerError> { ctx.assertReady() }

        // Two different bugs in the calling code; one message would hide which.
        assertEquals("core:player/disposed", failure.code)
    }

    @Test
    fun disposingIsRefusedFromTheMomentItStartsNotWhenItFinishes() {
        val ctx = newContext()
        ctx.transitionPhase(PlayerPhase.DISPOSING)

        assertEquals(
            "core:player/disposed",
            assertFailsWith<PlayerError> { ctx.assertReady() }.code,
        )
    }

    @Test
    fun aReadyPlayerPassesTheGuard() {
        val ctx = newContext()
        ctx.transitionPhase(PlayerPhase.PLAYING)

        ctx.assertReady()
    }

    @Test
    fun loadingHandsTheEngineAnAuthorisedUrl() = runTest {
        val ctx = newContext()
        ctx.auth = SigningAuth()

        ctx.load(TestItem("a"))

        // Every transport route ends up here, so a per-library loader cannot
        // forget about authorisation.
        assertEquals(listOf("https://example.test/a?token=abc"), ctx.fakeBackend().loadedUrls)
    }

    @Test
    fun loadingWithoutAnAuthControllerUsesTheUrlAsGiven() = runTest {
        val ctx = newContext()

        ctx.load(TestItem("a"))

        assertEquals(listOf("https://example.test/a"), ctx.fakeBackend().loadedUrls)
    }

    @Test
    fun loadingPassesItsOptionsThroughAndClearsTheEndingSoonLatch() = runTest {
        val ctx = newContext()
        ctx.itemEndingSoonEmitted = true

        ctx.load(TestItem("a"), LoadOptions(startPositionMs = 5_000, autoplay = true))

        val opts = ctx.fakeBackend().loadedOptions.single()
        assertEquals(5_000, opts.startPositionMs)
        assertTrue(opts.autoplay)
        // Cleared so itemEndingSoon fires once per item rather than never again.
        assertFalse(ctx.itemEndingSoonEmitted)
    }

    @Test
    fun aContextWithNoBackendSaysSoRatherThanLoadingNothing() = runTest {
        // This used to be a no-op on the reasoning that setup wires the backend
        // and a load before that is harmless. It is not harmless: the load
        // recorded the item and announced mediaReady, so a caller was told the
        // media was ready by a player that had nowhere to play it. The web
        // raises core:player/backend-missing here and so does this.
        val ctx = PlayerContext()

        val raised = assertFailsWith<StateError> { ctx.load(TestItem("a")) }

        assertEquals(CoreErrorCodes.BACKEND_MISSING, raised.code)
    }

    @Test
    fun anItemWithNoUrlIsRefusedBeforeTheEngineSeesIt() = runTest {
        val ctx = PlayerContext()

        val raised = assertFailsWith<MediaFormatError> { ctx.load(TestItem("a").copy(url = "")) }

        assertEquals(CoreErrorCodes.MISSING_URL, raised.code)
    }
}
