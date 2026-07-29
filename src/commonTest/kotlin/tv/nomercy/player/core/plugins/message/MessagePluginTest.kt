// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.message

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagePluginTest {

    @Test
    fun aToastShowsAndHidesItself() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.show("Skipping intro", ms = 1_000)
            assertEquals("Skipping intro", plugin.toast.value)

            advanceTimeBy(1_100)
            assertNull(plugin.toast.value)
        }
    }

    // Two notifications about the same action are one notification and one
    // piece of stale text.
    @Test
    fun aSecondToastReplacesTheFirstRatherThanQueueing() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.show("first", ms = 5_000)
            plugin.show("second", ms = 5_000)

            assertEquals("second", plugin.toast.value)

            // And the first one's timer must not take the second one down early.
            advanceTimeBy(4_000)
            assertEquals("second", plugin.toast.value)
        }
    }

    // Zero means "until something replaces it". The web's timer never fires for
    // a non-positive delay, and treating it as hide-now makes the message flash.
    @Test
    fun aZeroDurationStays() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.show("stays", ms = 0)
            advanceTimeBy(60_000)

            assertEquals("stays", plugin.toast.value)
        }
    }

    @Test
    fun aQueuePlaysInTurn() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.queue(
                listOf(
                    Message("one", durationMs = 1_000),
                    Message("two", durationMs = 1_000),
                ),
            )

            assertEquals("one", plugin.toast.value)
            advanceTimeBy(1_100)
            assertEquals("two", plugin.toast.value)
            advanceTimeBy(1_100)
            assertNull(plugin.toast.value)
        }
    }

    // Two sequences interleaving produce an order neither caller asked for.
    @Test
    fun asecondQueueCancelsTheFirst() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.queue(listOf(Message("old", durationMs = 5_000)))
            plugin.queue(listOf(Message("new", durationMs = 5_000)))

            assertEquals("new", plugin.toast.value)
            advanceTimeBy(4_000)
            assertEquals("new", plugin.toast.value)
        }
    }

    // A caller saying something directly has overtaken the sequence; letting it
    // continue would replace their message a second later.
    @Test
    fun aDirectMessageCancelsARunningQueue() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.queue(listOf(Message("queued", durationMs = 1_000), Message("also queued")))
            plugin.show("direct", ms = 5_000)

            advanceTimeBy(2_000)
            assertEquals("direct", plugin.toast.value)
        }
    }

    // A toast must not wipe "recording" or "casting to Living Room".
    @Test
    fun aToastDoesNotDisturbThePersistentMessages() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.showPersistent("cast", "Casting to Living Room")
            plugin.show("Skipping intro", ms = 500)
            advanceTimeBy(600)

            assertNull(plugin.toast.value)
            assertEquals(mapOf("cast" to "Casting to Living Room"), plugin.persistent.value)
        }
    }

    // A status that updates must not leave a trail of stale copies.
    @Test
    fun thesameIdReplacesRatherThanStacks() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.showPersistent("encode", "Encoding 10%")
            plugin.showPersistent("encode", "Encoding 90%")

            assertEquals(1, plugin.persistent.value.size)
            assertEquals("Encoding 90%", plugin.persistent.value["encode"])
        }
    }

    @Test
    fun severalPersistentMessagesCoexist() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.showPersistent("a", "Recording")
            plugin.showPersistent("b", "Offline")

            assertEquals(2, plugin.persistent.value.size)

            plugin.removePersistent("a")
            assertEquals(setOf("b"), plugin.persistent.value.keys)
        }
    }

    @Test
    fun clearTakesEverythingDown() = runTest {
        val plugin = MessagePlugin()
        // The test scope, not FakePlayer's default. Its default is a real
        // dispatcher the test scheduler does not drive, so advanceTimeBy would
        // move virtual time past a delay that never started.
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.showPersistent("a", "Recording")
            plugin.show("toast", ms = 5_000)
            plugin.clear()

            assertNull(plugin.toast.value)
            assertTrue(plugin.persistent.value.isEmpty())
        }
    }
}
