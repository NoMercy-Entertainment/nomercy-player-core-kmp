// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.ItemChange
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.events.SeekPosition
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.plugin.fakes.RecordingTransportCommands
import tv.nomercy.player.core.ports.TransportPlaybackState
import tv.nomercy.player.core.ports.fakes.FakeSystemTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class DemoItem(
    override val id: String = "1",
    override val url: String = "https://media.example.test/blade-runner-2049.mkv",
    override val title: String? = "Blade Runner 2049",
) : PlaylistItem

// Both halves of the conversation, over the player's real event bus.
//
// The bus is real because the mapping is the whole plugin: which event becomes
// which state, and which of them takes the item off the lock screen. A fake bus
// would let this pass while the events it subscribes to were renamed underneath
// it.
class MediaSessionPluginTest {

    private class Wiring(
        val player: ComposedPlayer,
        val plugin: MediaSessionPlugin,
        val transport: FakeSystemTransport,
        val commands: RecordingTransportCommands,
    )

    private suspend fun wire(): Wiring {
        val transport = FakeSystemTransport()
        val commands = RecordingTransportCommands()
        val plugin = MediaSessionPlugin(commands = commands, openTransport = { transport })
        val player = ComposedPlayer(backend = null)

        player.setup(PlayerConfig())
        player.addPlugin(plugin)
        return Wiring(player, plugin, transport, commands)
    }

    @Test
    fun theItemThatStartsPlayingReachesTheLockScreen() = runTest {
        val wiring: Wiring = wire()

        wiring.player.emit(CoreEvents.Item, ItemChange(item = DemoItem(), index = 0))

        assertEquals("Blade Runner 2049", wiring.transport.lastNowPlaying?.title)
    }

    @Test
    fun anItemWithNoTitleFallsBackToItsFileName() = runTest {
        // A local file added by hand. Showing the whole URL on a lock screen is
        // worse than showing the part of it a person recognises.
        val wiring: Wiring = wire()

        wiring.player.emit(
            CoreEvents.Item,
            ItemChange(item = DemoItem(title = null), index = 0),
        )

        assertEquals("blade-runner-2049.mkv", wiring.transport.lastNowPlaying?.title)
    }

    @Test
    fun playAndPauseReachTheSystemAsStates() = runTest {
        val wiring: Wiring = wire()

        wiring.player.emit(CoreEvents.Play, PlaySource())
        assertEquals(TransportPlaybackState.PLAYING, wiring.transport.lastState)

        wiring.player.emit(CoreEvents.Pause, PlaySource())
        assertEquals(TransportPlaybackState.PAUSED, wiring.transport.lastState)
    }

    @Test
    fun stoppingTakesTheItemOffTheLockScreenAndEndingDoesNot() = runTest {
        // The difference that matters on a queue. Ended is one track finishing
        // with another about to start; clearing there makes the notification
        // blink out between every one of them. Stop is a viewer closing the
        // player, and leaving the item up after that is a lock screen still
        // offering to control something that is gone.
        val ending: Wiring = wire()
        ending.player.emit(CoreEvents.Ended, Unit)
        assertEquals(TransportPlaybackState.STOPPED, ending.transport.lastState)
        assertFalse(ending.transport.cleared, "the end of a track blanked the lock screen")

        val stopping: Wiring = wire()
        stopping.player.emit(CoreEvents.Stop, PlaySource())
        assertTrue(stopping.transport.cleared, "stopping left the item on the lock screen")
    }

    @Test
    fun aTimeTickDoesNotTalkToTheOperatingSystem() = runTest {
        // Every one of these systems is given a position and a rate and works
        // the rest out itself. A push per tick is several a second of
        // cross-process traffic telling a lock screen what it already knew.
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())
        val before: Int = wiring.transport.pushes.size

        repeat(10) { tick ->
            wiring.player.emit(
                CoreEvents.Time,
                TimeUpdate(time = tick.toDouble(), duration = 100.0, percentage = tick.toDouble()),
            )
        }

        assertEquals(before, wiring.transport.pushes.size, "a time tick reached the operating system")
    }

    @Test
    fun aSeekDoesTalkToTheOperatingSystemBecauseItCannotBeInferred() = runTest {
        // The one thing a system extrapolating from position and rate cannot
        // work out. Without this the lock screen's scrubber walks on from where
        // the viewer left rather than from where they landed.
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())

        wiring.player.emit(CoreEvents.Seek, SeekPosition(time = 30.0))

        assertEquals(30_000, wiring.transport.lastPositionMs)
        assertEquals(TransportPlaybackState.PLAYING, wiring.transport.lastState, "the seek changed the state")
    }

    @Test
    fun thePositionPushedIsWhereverTheTicksHadReached() = runTest {
        // The ticks are not silent, they are unpushed. A pause after two minutes
        // has to say two minutes, which means the ticks were being remembered.
        val wiring: Wiring = wire()

        wiring.player.emit(
            CoreEvents.Time,
            TimeUpdate(time = 120.0, duration = 6_000.0, percentage = 2.0),
        )
        wiring.player.emit(CoreEvents.Pause, PlaySource())

        assertEquals(120_000, wiring.transport.lastPositionMs)
    }

    @Test
    fun aNewItemStartsFromTheBeginningRatherThanTheLastOnesPosition() = runTest {
        // Otherwise the second track's lock screen opens ninety minutes in,
        // because that is where the first one ended.
        val wiring: Wiring = wire()
        wiring.player.emit(
            CoreEvents.Time,
            TimeUpdate(time = 5_400.0, duration = 5_400.0, percentage = 100.0),
        )

        wiring.player.emit(CoreEvents.Item, ItemChange(item = DemoItem(id = "2"), index = 1))
        wiring.player.emit(CoreEvents.Play, PlaySource())

        assertEquals(0, wiring.transport.lastPositionMs)
    }

    @Test
    fun anExhaustedQueueClearsRatherThanKeepingTheLastItemUp() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Item, ItemChange(item = DemoItem(), index = 0))

        wiring.player.emit(CoreEvents.Item, ItemChange(item = null, index = 1))

        assertTrue(wiring.transport.cleared, "the last item stayed on the lock screen")
    }

    @Test
    fun everyButtonTheSystemOffersReachesThePlayer() = runTest {
        val wiring: Wiring = wire()

        wiring.transport.simulateOsAction("play")
        wiring.transport.simulateOsAction("pause")
        wiring.transport.simulateOsAction("next")
        wiring.transport.simulateOsAction("previous")
        wiring.transport.simulateOsAction("stop")
        wiring.transport.simulateOsSeek(30_000)

        assertEquals(
            listOf("play", "pause", "next", "previous", "stop", "seekTo:30000"),
            wiring.commands.calls,
        )
    }

    @Test
    fun theTransportIsReleasedWhenThePlayerGoesAway() = runTest {
        // A session that outlives its player is a notification a viewer can
        // press with nothing behind it, and on Android it is also a service that
        // never stops.
        val wiring: Wiring = wire()

        wiring.player.dispose()

        assertTrue(wiring.transport.released, "the transport outlived the player that owned it")
    }
}
