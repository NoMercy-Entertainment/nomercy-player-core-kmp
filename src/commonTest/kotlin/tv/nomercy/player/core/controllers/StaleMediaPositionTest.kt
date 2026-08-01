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
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Whose position the engine is reporting, while an advance is in flight.
//
// The cursor moves before the media does, so for the length of a mount the
// engine still holds the OUTGOING item and still ticks its position. Anything
// that believes those ticks writes the previous item's end position against the
// next item — which is how a viewer watching in order finds episodes they never
// opened marked as finished.
class StaleMediaPositionTest {

    private fun FakeMediaBackend.tick(position: Double, total: Double) {
        currentTimeValue = position
        durationValue = total
        fire(CanonicalBackendEvent.TIME_UPDATE)
    }

    @Test
    fun theOutgoingEnginesPositionIsNotPublishedAgainstTheIncomingItem() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("ep-a"), TestItem("ep-b")))
        player.setup()
        player.play()

        backend.tick(position = 1300.0, total = 1400.0)
        assertEquals(1300.0, player.time())

        val published: MutableList<TimeUpdate> = mutableListOf()
        player.on(CoreEvents.Time) { published += it }

        // What an advance does first, and all it has done while the mount runs.
        player.context.queue.setCurrent(1)

        backend.tick(position = 1305.0, total = 1400.0)

        assertTrue(published.isEmpty(), "the outgoing engine's tick reached listeners: $published")
        assertEquals(0.0, player.time())
        assertEquals(0.0, player.duration())
    }

    @Test
    fun positionUpdatesResumeOnceTheIncomingItemIsMounted() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("ep-a"), TestItem("ep-b")))
        player.setup()
        player.play()

        player.context.queue.setCurrent(1)
        player.context.load(TestItem("ep-b"))

        backend.tick(position = 12.0, total = 1400.0)

        assertEquals(12.0, player.time())
    }
}
