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
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.plugin.fakes.RecordingTransportCommands
import tv.nomercy.player.core.ports.SystemTransport
import tv.nomercy.player.core.ports.fakes.FakeSystemTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// One owner of the operating system's transport, and only one.
//
// This is the invariant the whole subsystem exists to restore. The shipped app
// has two — a music service and a video service, each with its own session —
// and which of them a notification's buttons reach depends on which registered
// last. The Android side of the proof is on hardware; this is the same claim at
// the contract layer, where it is a property of the plugin rather than of
// Media3.
class SingleOwnerContractTest {

    private class Opened(
        val player: ComposedPlayer,
        val transports: MutableList<FakeSystemTransport>,
    )

    private suspend fun open(): Opened {
        val transports: MutableList<FakeSystemTransport> = mutableListOf()
        val player = ComposedPlayer(backend = null)
        val plugin = MediaSessionPlugin(
            commands = RecordingTransportCommands(),
            openTransport = { FakeSystemTransport().also { transports += it } },
        )

        player.setup(PlayerConfig())
        player.addPlugin(plugin)
        return Opened(player, transports)
    }

    @Test
    fun installingThePluginOpensExactlyOneTransport() {
        runTest {
            val opened: Opened = open()

            assertEquals(1, opened.transports.size, "the plugin opened ${opened.transports.size} transports")
        }
    }

    @Test
    fun aSecondPluginOnTheSamePlayerIsRefusedBeforeItOpensAnything() {
        // Two media-session plugins is the two-owner bug in its purest form, and
        // the registry already refuses it: same manifest id, so the second
        // registration is rejected unless it declares that it replaces the
        // first. What matters here is the ORDER — the refusal happens during
        // validation, before use() runs, so the refused plugin never opens a
        // transport that would then have nothing releasing it.
        runTest {
            val transports: MutableList<FakeSystemTransport> = mutableListOf()
            val player = ComposedPlayer(backend = null)
            val build: () -> SystemTransport = { FakeSystemTransport().also { transports += it } }

            player.setup(PlayerConfig())
            player.addPlugin(MediaSessionPlugin(RecordingTransportCommands(), build))

            val refused: Boolean = try {
                player.addPlugin(MediaSessionPlugin(RecordingTransportCommands(), build))
                false
            } catch (rejected: PlayerError) {
                assertTrue(
                    rejected.message.orEmpty().contains(MediaSessionPlugin.id),
                    "the refusal did not name the plugin: ${rejected.message}",
                )
                true
            }

            assertTrue(refused, "a second media-session plugin was accepted")
            assertEquals(
                1,
                transports.size,
                "the refused plugin opened a transport nothing will release",
            )
        }
    }

    @Test
    fun theTransportIsReleasedBeforeAnotherPlayerCouldTakeIt() {
        // A player torn down and another started is the ordinary case on a
        // queue-driven app. The first has to let go, or the second is the second
        // owner.
        runTest {
            val first: Opened = open()

            first.player.dispose()

            assertTrue(first.transports.single().released, "the first player kept the system transport")
        }
    }

    @Test
    fun whatTheSystemShowsComesFromOnePlace() {
        // With one owner there is one answer. The test that matters is that the
        // one transport is the one being pushed to — a second, orphaned
        // transport would receive nothing and be invisible to every other test
        // here.
        runTest {
            val opened: Opened = open()

            opened.player.emit(CoreEvents.Play, PlaySource())

            assertTrue(
                opened.transports.single().pushes.any { it.startsWith("state:") },
                "the live transport was never told anything",
            )
        }
    }
}
