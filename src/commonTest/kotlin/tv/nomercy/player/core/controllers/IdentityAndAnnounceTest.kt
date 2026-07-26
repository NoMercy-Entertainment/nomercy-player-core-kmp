// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.AnnouncementLevel
import tv.nomercy.player.core.ports.Announcer
import tv.nomercy.player.core.player.PlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Who this player is, what it was configured with, and what it says out loud.
class IdentityAndAnnounceTest {

    private fun player(): ComposedPlayer = ComposedPlayer(backend = FakeMediaBackend())

    // What the chrome would do with a platform accessibility API, recorded.
    private class RecordingAnnouncer : Announcer {
        val spoken: MutableList<Pair<String, AnnouncementLevel>> = mutableListOf()
        override fun announce(text: String, level: AnnouncementLevel) {
            spoken += text to level
        }
    }

    private fun speaking(announcer: Announcer): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), announcer = announcer)

    @Test
    fun aPlayerHasAnIdWithoutBeingGivenOne() {
        // A required id would be a construction argument every caller has to
        // invent before it can build anything.
        assertTrue(player().playerId.isNotEmpty())
    }

    @Test
    fun twoPlayersInOneProcessDoNotShareAnId() {
        // The whole reason it exists: telling the video player and the music
        // player apart in one log.
        assertNotEquals(player().playerId, player().playerId)
    }

    @Test
    fun aHostThatCaresCanNameIt() {
        assertEquals("music", ComposedPlayer(backend = FakeMediaBackend(), playerId = "music").playerId)
    }

    @Test
    fun theConfigurationIsReadableBeforeSetupRuns() = runTest {
        // A chrome asking how long the autohide window is should get the answer
        // it will have, not a null it has to handle on every field.
        val player: ComposedPlayer = player()

        assertEquals(PlayerConfig(), player.options())

        player.setup(PlayerConfig(inactivityMs = 9_000L))

        assertEquals(9_000L, player.options().inactivityMs)
    }

    @Test
    fun anAnnouncementReachesTheChromeWithItsUrgency() {
        // Core cannot speak: it has no view to hang a live region on and no
        // business holding a platform accessibility handle. The chrome that has
        // both supplies the port.
        val announcer = RecordingAnnouncer()

        speaking(announcer).apply {
            announce("Now playing: episode two")
            announce("Playback failed", AnnouncementLevel.ASSERTIVE)
        }

        assertEquals(
            listOf(
                "Now playing: episode two" to AnnouncementLevel.POLITE,
                "Playback failed" to AnnouncementLevel.ASSERTIVE,
            ),
            announcer.spoken,
        )
    }

    @Test
    fun politeIsTheDefaultBecauseMostThingsCanWait() {
        val announcer = RecordingAnnouncer()

        speaking(announcer).announce("Subtitles on")

        assertEquals(listOf("Subtitles on" to AnnouncementLevel.POLITE), announcer.spoken)
    }

    @Test
    fun anEmptyAnnouncementIsNotMade() {
        // A screen reader interrupting itself to say nothing is worse than
        // silence, and a formatted string that came out empty is the usual way
        // it happens.
        val announcer = RecordingAnnouncer()

        speaking(announcer).apply {
            announce("")
            announce("   ")
        }

        assertEquals(emptyList(), announcer.spoken)
    }

    @Test
    fun aPlayerWithNoAnnouncerStaysSilentRatherThanThrowing() {
        // A headless player, a test harness, a cast sender with no UI. Every
        // one of them calls this and none of them should fall over.
        player().announce("Now playing: episode two")
    }
}
