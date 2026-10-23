// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The session a car is bound to must outlive every playback that comes and goes.
class SwappableSessionTest {

    private class Player(val label: String)

    private class Session(val firstPlayer: Player) {
        var current: Player = firstPlayer
        var disposed = false
    }

    private class Harness {
        var sessionsBuilt = 0
        val idlePlayers = mutableListOf<Player>()
        val swaps = mutableListOf<Player>()

        val slot = SwappableSession<Session, Player>(
            newIdle = { Player("idle").also { idlePlayers += it } },
            build = { player ->
                sessionsBuilt++
                Session(player)
            },
            setPlayer = { session, player ->
                swaps += player
                session.current = player
            },
            dispose = { session -> session.disposed = true },
        )
    }

    @Test
    fun theSessionIsBuiltOnceAndStartsOnTheIdlePlayer() {
        val harness = Harness()

        val first = harness.slot.obtain()
        val second = harness.slot.obtain()

        assertSame(first, second)
        assertEquals(1, harness.sessionsBuilt)
        assertSame(harness.idlePlayers.single(), first.current)
    }

    @Test
    fun attachingPlaybackSwapsThePlayerInsteadOfBuildingAnotherSession() {
        val harness = Harness()
        val browse = harness.slot.obtain()
        val playback = Player("playback")

        val served = harness.slot.attach(playback)

        assertSame(browse, served)
        assertSame(playback, browse.current)
        assertEquals(1, harness.sessionsBuilt)
        assertFalse(browse.disposed)
    }

    @Test
    fun detachingPlaybackHandsTheSameSessionBackToTheIdlePlayer() {
        val harness = Harness()
        val session = harness.slot.obtain()
        val playback = Player("playback")
        harness.slot.attach(playback)

        assertTrue(harness.slot.detach(playback))

        assertSame(harness.idlePlayers.single(), session.current)
        assertFalse(session.disposed)
        assertNull(harness.slot.attached)
    }

    @Test
    fun aSupersededPlayerDetachingLateLeavesItsSuccessorAttached() {
        val harness = Harness()
        val session = harness.slot.obtain()
        val older = Player("older")
        val newer = Player("newer")
        harness.slot.attach(older)
        harness.slot.attach(newer)

        assertFalse(harness.slot.detach(older))

        assertSame(newer, session.current)
        assertTrue(harness.slot.isAttached(newer))
        assertFalse(harness.slot.isAttached(older))
    }

    @Test
    fun attachingTheSamePlayerTwiceSwapsOnce() {
        val harness = Harness()
        val playback = Player("playback")
        harness.slot.attach(playback)
        harness.slot.attach(playback)

        assertEquals(listOf(playback), harness.swaps)
    }

    @Test
    fun theSessionIsNeverDisposedWhilePlaybackIsAttached() {
        val harness = Harness()
        val session = harness.slot.obtain()
        harness.slot.attach(Player("playback"))

        assertFalse(harness.slot.releaseIfIdle())

        assertFalse(session.disposed)
    }

    @Test
    fun anIdleSessionIsDisposedAndTheNextObtainBuildsAFreshOne() {
        val harness = Harness()
        val old = harness.slot.obtain()
        val playback = Player("playback")
        harness.slot.attach(playback)
        harness.slot.detach(playback)

        assertTrue(harness.slot.releaseIfIdle())
        val fresh = harness.slot.obtain()

        assertTrue(old.disposed)
        assertNotSame(old, fresh)
        assertEquals(2, harness.sessionsBuilt)
    }

    @Test
    fun releasingWhenNothingWasBuiltDisposesNothing() {
        val harness = Harness()

        assertFalse(harness.slot.releaseIfIdle())
        assertEquals(0, harness.sessionsBuilt)
    }

    @Test
    fun aFullCycleBuildsOneSessionAndDisposesNone() {
        val harness = Harness()
        val session = harness.slot.obtain()
        repeat(3) { index ->
            val playback = Player("playback-$index")
            harness.slot.attach(playback)
            harness.slot.detach(playback)
        }

        assertEquals(1, harness.sessionsBuilt)
        assertFalse(session.disposed)
        assertSame(harness.idlePlayers.single(), session.current)
    }
}
