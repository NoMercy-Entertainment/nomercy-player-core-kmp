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
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the player says an item's length is.
 *
 * The cases are the web's `nomercy-music-player/__tests__/duration-resolution.test.ts`,
 * which is where the live-stream branch was written down. The native guard read
 * `duration > 0.0`, and infinity satisfies that — so a live stream latched an
 * infinite length into the player's own state and published it.
 */
class DurationResolutionTest {

    private fun player(backend: FakeMediaBackend): ComposedPlayer =
        ComposedPlayer(backend = backend).also { it.queue(listOf(TestItem("a"))) }

    @Test
    fun theLengthIsPickedUpOnceTheStreamFinallyReportsOne() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<Double> = mutableListOf()
        subject.on(CoreEvents.Duration) { seconds -> seen += seconds }

        backend.fire(CanonicalBackendEvent.LOADED_METADATA)
        assertEquals(emptyList(), seen, "a length was announced before there was one")

        backend.durationValue = LENGTH
        backend.fire(CanonicalBackendEvent.DURATION_CHANGE)

        assertEquals(listOf(LENGTH), seen)
    }

    @Test
    fun aSteadyStreamDoesNotReAnnounceTheSameLength() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<Double> = mutableListOf()
        subject.on(CoreEvents.Duration) { seconds -> seen += seconds }

        backend.durationValue = LENGTH
        backend.fire(CanonicalBackendEvent.DURATION_CHANGE)
        backend.fire(CanonicalBackendEvent.DURATION_CHANGE)

        assertEquals(listOf(LENGTH), seen, "the same length was announced twice")
    }

    /**
     * A live stream reports its length as infinite, and that is not a length.
     *
     * Published, it reaches a progress bar as a denominator that makes every
     * position zero, so the bar never moves; and latched into the player's own
     * duration it reaches the ending-soon window and the preload trigger, which
     * both subtract from it.
     */
    @Test
    fun theInfinityALiveStreamReportsIsNeverPublished() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<Double> = mutableListOf()
        subject.on(CoreEvents.Duration) { seconds -> seen += seconds }

        backend.durationValue = Double.POSITIVE_INFINITY
        backend.fire(CanonicalBackendEvent.LOADED_METADATA)
        backend.fire(CanonicalBackendEvent.DURATION_CHANGE)

        assertEquals(emptyList(), seen, "an infinite length was announced")
    }

    @Test
    fun norIsItLatchedIntoThePlayersOwnDuration() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)

        backend.durationValue = Double.POSITIVE_INFINITY
        backend.fire(CanonicalBackendEvent.LOADED_METADATA)

        assertTrue(
            subject.duration().isFinite(),
            "the player's duration is ${subject.duration()}",
        )
    }

    @Test
    fun anUnreadableLengthIsNotALengthEither() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<Double> = mutableListOf()
        subject.on(CoreEvents.Duration) { seconds -> seen += seconds }

        backend.durationValue = Double.NaN
        backend.fire(CanonicalBackendEvent.DURATION_CHANGE)

        assertEquals(emptyList(), seen, "NaN was announced as a length")
    }

    private companion object {
        const val LENGTH = 524.0
    }
}
