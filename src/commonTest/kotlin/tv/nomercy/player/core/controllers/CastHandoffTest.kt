// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CastTarget
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.CastState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.CastSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

private val LIVING_ROOM = CastTarget(id = "chromecast-3", name = "Living room TV", kind = "cast")

// What a cast plugin would do, recorded.
private class RecordingSender(
    private val accepts: Boolean = true,
    private val remotePosition: Double? = null,
) : CastSender {
    var handedOver: Triple<CastTarget, String?, Double>? = null
        private set
    var reclaims: Int = 0
        private set

    override suspend fun transfer(target: CastTarget, item: PlaylistItem?, position: Double): Boolean {
        handedOver = Triple(target, item?.id, position)
        return accepts
    }

    override suspend fun reclaim(): Double? {
        reclaims += 1
        return remotePosition
    }
}

// Handing playback to the room and taking it back.
//
// Core cannot cast — the protocols belong to a plugin — so every assertion here
// is about the choreography core does own: ask permission, stop locally, hand
// over the position, report the state.
class CastHandoffTest {

    private suspend fun playingAt(seconds: Double, sender: CastSender?): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, castSender = sender)
        player.queue(listOf(TestItem("episode-2")))
        player.setup()
        player.play()
        backend.currentTimeValue = seconds
        backend.durationValue = 2_400.0
        backend.fire(CanonicalBackendEvent.TIME_UPDATE)
        return player to backend
    }

    @Test
    fun theRemoteEndIsToldWhereTheViewerHadGotTo() = runTest {
        // A handoff that restarts the episode is the single thing that makes
        // people stop using the feature.
        val sender = RecordingSender()
        val (player, _) = playingAt(742.0, sender)

        assertTrue(player.transferTo(LIVING_ROOM))

        assertEquals(Triple(LIVING_ROOM, "episode-2", 742.0), sender.handedOver)
    }

    @Test
    fun theHandoffPauseSaysWhoAskedForIt() = runTest {
        // PlaySource(source=null) reaches a listener as "nobody knows who asked",
        // and this caller knows: the library paused so the television could pick
        // the film up. A plugin dimming a lamp on a viewer's pause would dim it
        // for a handoff to the next room without this.
        val sender = RecordingSender()
        val (player, _) = playingAt(100.0, sender)
        val sources: MutableList<String?> = mutableListOf()
        player.on(CoreEvents.Pause) { sources += it.source }

        player.transferTo(LIVING_ROOM)

        assertEquals(listOf<String?>("platform"), sources)
    }

    @Test
    fun playbackStopsHereBeforeItStartsThere() = runTest {
        // Two devices playing the same thing a second apart is the audible
        // failure of getting this order wrong.
        val sender = RecordingSender()
        val (player, backend) = playingAt(100.0, sender)

        player.transferTo(LIVING_ROOM)

        assertEquals(1, backend.pauseCount, "the local engine was still playing when the handoff went out")
    }

    @Test
    fun theStateWalksThroughConnectingToConnected() = runTest {
        // A chrome shows a spinner on the cast icon for exactly this window.
        val (player, _) = playingAt(0.0, RecordingSender())
        val states: MutableList<Any?> = mutableListOf()
        player.on(CoreEvents.CastState) { states += it.state }

        player.transferTo(LIVING_ROOM)

        val expected: List<Any?> = listOf("connecting", "connected")
        assertEquals(expected, states)
        assertEquals(CastState.CONNECTED, player.castState())
    }

    @Test
    fun aRefusedHandoffLeavesTheTargetMerelyAvailable() = runTest {
        // The device was there and said no. Reporting CONNECTED would leave a
        // chrome showing a session that does not exist.
        val (player, _) = playingAt(0.0, RecordingSender(accepts = false))

        assertFalse(player.transferTo(LIVING_ROOM))

        assertEquals(CastState.AVAILABLE, player.castState())
    }

    @Test
    fun aListenerCanVetoTheHandoff() = runTest {
        // The seam a chrome uses to ask 'you have unsaved edits, still cast?'
        // and mean it.
        val sender = RecordingSender()
        val (player, backend) = playingAt(100.0, sender)
        player.on(CoreEvents.BeforeTransfer) { it.preventDefault() }
        var prevented = false
        player.on(CoreEvents.TransferPrevented) { prevented = true }

        assertFalse(player.transferTo(LIVING_ROOM))

        assertTrue(prevented)
        assertEquals(null, sender.handedOver, "a vetoed transfer still reached the sender")
        assertEquals(0, backend.pauseCount, "a vetoed transfer paused local playback anyway")
    }

    @Test
    fun comingBackResumesWhereTheRemoteGotTo() = runTest {
        // The viewer walked out of the living room. They pick up where the
        // television was, not where this device was when they left it.
        val sender = RecordingSender(remotePosition = 1_200.0)
        val (player, backend) = playingAt(100.0, sender)
        player.transferTo(LIVING_ROOM)

        assertTrue(player.transferTo(null))

        assertEquals(1, sender.reclaims)
        assertEquals(1_200.0, player.time())
        assertTrue(backend.seekedTo.contains(1_200.0), "the local engine was not moved to the remote position")
    }

    @Test
    fun aRemoteThatCannotSayWhereItGotToDoesNotRestartTheItem() = runTest {
        // Seeking to zero on the way home would replay everything watched on
        // the television.
        val sender = RecordingSender(remotePosition = null)
        val (player, backend) = playingAt(300.0, sender)
        player.transferTo(LIVING_ROOM)
        val seeksBefore: Int = backend.seekedTo.size

        player.transferTo(null)

        assertEquals(seeksBefore, backend.seekedTo.size, "an unknown remote position caused a seek")
        assertEquals(CastState.DISCONNECTED, player.castState())
    }

    @Test
    fun aBuildWithNoCastPluginSaysSoRatherThanCrashing() = runTest {
        // A chrome offering a cast button on a build without the plugin should
        // find out by asking.
        val (player, _) = playingAt(0.0, sender = null)
        var reason: String? = null
        player.on(CoreEvents.TransferPrevented) { reason = it.reason }

        assertFalse(player.transferTo(LIVING_ROOM))

        assertEquals("no-cast-sender", reason)
    }
}
