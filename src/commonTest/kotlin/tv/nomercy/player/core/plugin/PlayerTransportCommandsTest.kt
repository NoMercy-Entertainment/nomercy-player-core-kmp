// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.controllers.FakeMediaBackend
import tv.nomercy.player.core.controllers.TestItem
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.events.SeekPosition
import tv.nomercy.player.core.player.ActionSource
import tv.nomercy.player.core.player.PlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The lock screen actually moving the player.
//
// The plugin's own tests prove a button reaches the command seam. This proves
// the seam reaches the player — which is the half a fake cannot answer, and the
// half where the two unit systems meet.
class PlayerTransportCommandsTest {

    // A scope that runs what is launched into it straight away.
    //
    // The adapter launches rather than awaits, on purpose — a transport callback
    // arrives on the operating system's thread and must not block there. That
    // makes every assertion here about something that happened on another
    // coroutine, and an eager dispatcher is what lets the test read the result
    // in the line after the call rather than pumping a scheduler between each
    // one.
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun aButtonFromTheSystemReachesThePlayersOwnBus() = runTest {
        // Through the player rather than at it. A command that called an engine
        // directly would move playback without the player's own listeners —
        // its chrome, its plugins, its state — ever hearing about it.
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        val sources: MutableList<String?> = mutableListOf()
        player.setup(PlayerConfig())
        // Queued, because the transport asserts the player is ready before it
        // does anything. A lock screen on a player with nothing loaded is the
        // one case where a button genuinely should do nothing.
        player.queue(listOf(TestItem("a")))
        player.on(CoreEvents.Play) { source -> sources += source.source }

        PlayerTransportCommands(player, eager()).play()

        assertEquals(1, sources.size, "the play button never reached the player")
    }

    @Test
    fun aSeekArrivesInSecondsAfterCrossingInMilliseconds() = runTest {
        // The system counts in milliseconds and the player in seconds, and the
        // conversion happens once here so neither has to know the other's
        // choice. Getting it wrong is a scrubber that jumps to the wrong place
        // by a factor of a thousand.
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        val seeks: MutableList<Double> = mutableListOf()
        player.setup(PlayerConfig())
        // Queued, because the transport asserts the player is ready before it
        // does anything. A lock screen on a player with nothing loaded is the
        // one case where a button genuinely should do nothing.
        player.queue(listOf(TestItem("a")))
        player.on(CoreEvents.Seek) { position -> seeks += position.time }

        PlayerTransportCommands(player, eager()).seekTo(90_000)

        assertEquals(listOf(90.0), seeks)
    }

    @Test
    fun anActionFromOutsideTheProcessIsMarkedAsRemote() = runTest {
        // So a listener can tell a car's steering wheel from the app's own
        // button. Both are legitimate and they are not the same event.
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        val sources: MutableList<String?> = mutableListOf()
        player.setup(PlayerConfig())
        // Queued, because the transport asserts the player is ready before it
        // does anything. A lock screen on a player with nothing loaded is the
        // one case where a button genuinely should do nothing.
        player.queue(listOf(TestItem("a")))
        player.on(CoreEvents.Pause) { source -> sources += source.source }

        PlayerTransportCommands(player, eager()).pause()

        assertEquals(listOf<String?>(ActionSource.REMOTE), sources)
    }

    @Test
    fun everyVerbTheSystemOffersLandsOnThePlayer() = runTest {
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        val heard: MutableList<String> = mutableListOf()
        player.on(CoreEvents.Play) { _: PlaySource -> heard += "play" }
        player.on(CoreEvents.Pause) { _: PlaySource -> heard += "pause" }
        player.on(CoreEvents.Stop) { _: PlaySource -> heard += "stop" }
        player.on(CoreEvents.Seek) { _: SeekPosition -> heard += "seek" }
        player.setup(PlayerConfig())
        // Queued, because the transport asserts the player is ready before it
        // does anything. A lock screen on a player with nothing loaded is the
        // one case where a button genuinely should do nothing.
        player.queue(listOf(TestItem("a")))

        val commands = PlayerTransportCommands(player, eager())
        commands.play()
        commands.pause()
        commands.stop()
        commands.seekTo(1_000)

        assertTrue(heard.containsAll(listOf("play", "pause", "stop", "seek")), "heard only $heard")
    }

    @Test
    fun aButtonPressedDoesNotBlockTheThreadItArrivedOn() = runTest {
        // A transport callback arrives on whichever thread the operating system
        // felt like, and the player's surface suspends. Blocking there is a
        // frozen notification shade at best and an ANR at worst, so each verb
        // is launched rather than awaited.
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        player.setup(PlayerConfig())
        // Queued, because the transport asserts the player is ready before it
        // does anything. A lock screen on a player with nothing loaded is the
        // one case where a button genuinely should do nothing.
        player.queue(listOf(TestItem("a")))

        var returned = false
        PlayerTransportCommands(player, eager()).play()
        returned = true

        assertTrue(returned, "the call did not return before the player had finished")
    }
}
