// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.leader

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.TransportCommands
import tv.nomercy.player.core.plugin.VolumeCommands
import tv.nomercy.player.core.ports.InProcessLeaderLock
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabLeaderPluginTest {

    @Test
    fun theFirstPlayerToAskHoldsTheLock() = runTest {
        val lock = InProcessLeaderLock()
        val commands = RecordingCommands()
        val plugin = TabLeaderPlugin(commands, commands, lock)

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            assertTrue(plugin.isLeader())
        }
    }

    // The whole point of the plugin: the second player waits, and takes over the
    // moment the first lets go. A queue that granted both would leave two
    // players sounding at once, which is the bug this exists to prevent.
    @Test
    fun theSecondPlayerWaitsAndIsHandedTheLockWhenTheFirstReleases() = runTest {
        val lock = InProcessLeaderLock()
        val first = RecordingCommands()
        val second = RecordingCommands()
        val leading = TabLeaderPlugin(first, first, lock)
        val waiting = TabLeaderPlugin(second, second, lock)

        testPlugin(leading, FakePlayer(scope = this)) { _, _ ->
            testPlugin(waiting, FakePlayer(scope = this)) { _, _ ->

                assertTrue(leading.isLeader())
                assertFalse(waiting.isLeader())

                leading.releaseLock()

                assertFalse(leading.isLeader())
                assertTrue(waiting.isLeader())
                assertEquals(listOf("pause"), first.calls)
            }
        }
    }

    // Withdrawing a request that never came up is not losing anything. Pausing
    // for it would stop a player that had been told to wait its turn and had.
    @Test
    fun aWaitingPlayerThatGivesUpIsNotPaused() = runTest {
        val lock = InProcessLeaderLock()
        val first = RecordingCommands()
        val second = RecordingCommands()
        val leading = TabLeaderPlugin(first, first, lock)
        val waiting = TabLeaderPlugin(second, second, lock)

        testPlugin(leading, FakePlayer(scope = this)) { _, _ ->
            testPlugin(waiting, FakePlayer(scope = this)) { _, _ ->

                waiting.releaseLock()

                assertEquals(emptyList(), second.calls)
                assertTrue(leading.isLeader(), "the holder keeps the lock a waiter gave up on")
            }
        }
    }

    @Test
    fun theMuteActionSilencesRatherThanStopping() = runTest {
        val lock = InProcessLeaderLock()
        val commands = RecordingCommands()
        val options = TabLeaderOptions(onLost = LeaderLostAction.MUTE)
        val plugin = TabLeaderPlugin(commands, commands, lock, options)

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            plugin.releaseLock()

            assertEquals(listOf("mute"), commands.calls)
        }
    }

    // Coming back to the screen asks for the lock again. Without it a player
    // that lost leadership in the background stays silent forever, and the
    // viewer's only fix is to leave the screen and come back — which is what
    // they just did.
    @Test
    fun becomingVisibleAsksForTheLockAgain() = runTest {
        val lock = InProcessLeaderLock()
        val first = RecordingCommands()
        val second = RecordingCommands()
        val leading = TabLeaderPlugin(first, first, lock)
        val returning = TabLeaderPlugin(second, second, lock)

        testPlugin(leading, FakePlayer(scope = this)) { _, _ ->
            testPlugin(returning, FakePlayer(scope = this)) { player, _ ->

                returning.releaseLock()
                assertFalse(returning.isLeader())

                player.emit(CoreEvents.VisibilityVisible, Unit)
                leading.releaseLock()

                assertTrue(returning.isLeader())
            }
        }
    }

    // A platform with no leadership authority says so once and gets out of the
    // way. Blocking playback because an election could not run would be the
    // library deciding a policy the consumer never asked for.
    @Test
    fun noLockMeansUnsupportedAndNothingElse() = runTest {
        val commands = RecordingCommands()
        val plugin = TabLeaderPlugin(commands, commands, lock = null)

        testPlugin(plugin, FakePlayer(scope = this)) { player, _ ->
            var unsupported = 0
            val watching: Subscription = player.on(TabLeaderEvents.UnsupportedOnPlayer) { unsupported += 1 }

            plugin.requestLock()
            watching.dispose()

            assertFalse(plugin.isLeader())
            assertEquals(1, unsupported)
            assertEquals(emptyList(), commands.calls)
        }
    }
}

private class RecordingCommands : TransportCommands, VolumeCommands {
    val calls: MutableList<String> = mutableListOf()

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
    }

    override fun next() {
        calls += "next"
    }

    override fun previous() {
        calls += "previous"
    }

    override fun volume(level: Int) {
        calls += "volume:$level"
    }

    override fun mute() {
        calls += "mute"
    }

    override fun unmute() {
        calls += "unmute"
    }
}
