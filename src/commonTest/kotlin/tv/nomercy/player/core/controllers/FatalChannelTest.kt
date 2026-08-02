// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.testing.FakeMediaBackend

// The fatal channel, which was declared and emitted by nothing.
//
// CoreEvents.Fatal was in the registry, Severity.FATAL existed, and no code path
// in core, video or music raised either — so a consumer subscribing to `fatal`
// heard nothing ever, and PlayState.ERROR was unreachable because the only thing
// that would write it was a fatal that never arrived.
class FatalChannelTest {

    private fun fatal(): PlayerError = PlayerError(
        code = "media.decode-failed",
        scope = ErrorScope.backend("engine"),
        severity = Severity.FATAL,
        message = "the decoder gave up",
        suggestion = "try another quality",
    )

    private fun player(): ComposedPlayer {
        val backend = FakeMediaBackend()
        return ComposedPlayer(backend)
    }

    @Test
    fun aFatalErrorReachesTheFatalChannel() {
        val subject = player()
        val seen: MutableList<PlayerErrorEvent> = mutableListOf()
        subject.on(CoreEvents.Fatal) { seen += it }

        subject.report(fatal())

        assertEquals(1, seen.size)
        assertEquals("media.decode-failed", seen.single().code)
        assertEquals("try another quality", seen.single().suggestion, "the whole payload travels, not just the code")
    }

    @Test
    fun theStateIsAlreadyErrorWhenTheFatalListenerRuns() {
        // The reference settles the state BEFORE the listener chain, so a
        // listener observes ERROR rather than the PLAYING it was a moment ago.
        // A chrome bound to play state would otherwise draw a playing player
        // over a dead one until something else happened to change it.
        val subject = player()
        var observed: PlayState? = null
        subject.on(CoreEvents.Fatal) { observed = subject.playState() }

        subject.report(fatal())

        assertEquals(PlayState.ERROR, observed)
    }

    @Test
    fun anOrdinaryErrorIsNotFatalAndLeavesTheStateAlone() {
        val subject = player()
        var reached = false
        subject.on(CoreEvents.Fatal) { reached = true }

        subject.report(
            PlayerError(
                code = "media.slow",
                scope = ErrorScope.backend("engine"),
                severity = Severity.ERROR,
            ),
        )

        assertTrue(!reached)
        assertEquals(PlayState.IDLE, subject.playState())
    }
}
