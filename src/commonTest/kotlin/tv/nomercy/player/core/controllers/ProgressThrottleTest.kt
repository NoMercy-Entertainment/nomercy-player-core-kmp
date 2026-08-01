// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.ProgressPayload
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

private class SteppableClock(var millis: Long = 0L) : Clock {
    override fun now(): Long = millis
}

// `progress` is what a consumer persists a watch position from, and it was
// coming off the engine's own tick — several writes a second, per viewer, for
// the whole of a film. progressIntervalMs existed and nothing read it.
class ProgressThrottleTest {

    private fun FakeMediaBackend.tick(position: Double, total: Double = 100.0) {
        currentTimeValue = position
        durationValue = total
        fire(CanonicalBackendEvent.TIME_UPDATE)
    }

    private suspend fun rig(config: PlayerConfig, clock: Clock): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, clock = clock)
        player.queue(listOf(TestItem("a")))
        player.setup(config)
        return player to backend
    }

    @Test
    fun theEnginesTickRateIsNotTheSaveRate() = runTest {
        val clock = SteppableClock()
        val (player, backend) = rig(PlayerConfig(progressIntervalMs = 5_000), clock)
        val seen: MutableList<ProgressPayload> = mutableListOf()
        player.on(CoreEvents.Progress) { seen += it }

        repeat(40) { step ->
            clock.millis += 100
            backend.tick(position = step.toDouble())
        }

        assertEquals(1, seen.size, "forty engine ticks inside one window is one save, not forty")
    }

    @Test
    fun theFirstTickAfterSetupAlwaysReports() = runTest {
        // A viewer who opened a player and closed it inside the first window
        // still watched something, and the last-emitted stamp starts at zero so
        // that first tick is never swallowed.
        val clock = SteppableClock(millis = 1_000)
        val (player, backend) = rig(PlayerConfig(), clock)
        val seen: MutableList<ProgressPayload> = mutableListOf()
        player.on(CoreEvents.Progress) { seen += it }

        backend.tick(position = 3.0)

        assertEquals(1, seen.size)
    }

    @Test
    fun crossingTheWindowReportsAgain() = runTest {
        val clock = SteppableClock()
        val (player, backend) = rig(PlayerConfig(progressIntervalMs = 5_000), clock)
        val seen: MutableList<ProgressPayload> = mutableListOf()
        player.on(CoreEvents.Progress) { seen += it }

        backend.tick(position = 1.0)
        clock.millis += 4_999
        backend.tick(position = 2.0)
        clock.millis += 1
        backend.tick(position = 3.0)

        assertEquals(listOf(1.0, 3.0), seen.map { it.time })
    }

    @Test
    fun theReportCarriesTheNumbersAScrubberBindsTo() = runTest {
        val clock = SteppableClock()
        val (player, backend) = rig(PlayerConfig(), clock)
        val seen: MutableList<ProgressPayload> = mutableListOf()
        player.on(CoreEvents.Progress) { seen += it }

        backend.tick(position = 25.0, total = 100.0)

        assertEquals(ProgressPayload(time = 25.0, duration = 100.0, percentage = 25.0), seen.single())
    }

    @Test
    fun zeroTurnsItOff() = runTest {
        // A host with its own persistence loop should not be paying for a
        // second one.
        val clock = SteppableClock()
        val (player, backend) = rig(PlayerConfig(progressIntervalMs = 0), clock)
        var reports = 0
        player.on(CoreEvents.Progress) { reports += 1 }

        repeat(10) { step ->
            clock.millis += 10_000
            backend.tick(position = step.toDouble())
        }

        assertEquals(0, reports)
    }

    @Test
    fun theEndingSoonWindowIsTheOneTheHostConfigured() = runTest {
        // itemEndingSoonThreshold had zero readers, so every player warned at
        // the built-in ten seconds whatever the host asked for.
        val clock = SteppableClock()
        val (player, backend) = rig(PlayerConfig(itemEndingSoonThreshold = 30.0), clock)
        var warnings = 0
        player.on(CoreEvents.ItemEndingSoon) { warnings += 1 }

        backend.tick(position = 75.0, total = 100.0)

        assertEquals(1, warnings, "25 seconds left is inside a 30-second window and outside the default 10")
    }
}
