// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// Skipping, and the two ends it must not run past.
//
// A rewind landing at a negative position makes one engine seek to the start and
// another refuse outright, so the clamp is not tidiness — it is the difference
// between a skip button that behaves the same everywhere and one that does not.
class SkipSurfaceTest {

    private suspend fun player(): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val subject = ComposedPlayer(backend = backend)
        subject.setup()
        subject.queue(listOf(TestItem("a")))
        return subject to backend
    }

    @Test
    fun rewindingPastTheStartLandsAtTheStart() = runTest {
        val (subject, _) = player()
        subject.time(5.0)

        subject.rewind(30.0)

        assertEquals(0.0, subject.time())
    }

    @Test
    fun skippingUsesTenSecondsWhenNobodySaysOtherwise() = runTest {
        // What every player's skip button does, and what a viewer expects
        // without being told.
        val (subject, _) = player()
        subject.time(30.0)

        subject.rewind()

        assertEquals(20.0, subject.time())
    }

    @Test
    fun forwardWorksOnAStreamWithNoKnownDuration() = runTest {
        // A live stream is where the duration is most often unknown, and
        // clamping against a zero duration would make every forward skip a
        // no-op there.
        val (subject, _) = player()
        subject.time(10.0)

        subject.forward(5.0)

        assertTrue(subject.time() > 10.0, "a forward skip did nothing on an unknown duration")
    }
}
