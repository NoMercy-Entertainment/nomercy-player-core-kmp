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
import tv.nomercy.player.core.events.StreamError
import tv.nomercy.player.core.ports.BackendEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

// An engine's stream failure, in the stream vocabulary.
//
// A chrome that wants to say "the connection dropped" rather than "playback
// failed" needs the distinction, and it is the engine that knows which one
// happened. Without the bridge the event is declared in the registry and never
// fires, which is the same as not having it — except a consumer can subscribe
// and wait forever.
class StreamErrorBridgeTest {

    @Test
    fun anEngineStreamFailureReachesTheStreamEvent() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.setup()
        var seen: StreamError? = null
        player.on(CoreEvents.StreamError) { seen = it }

        backend.fire(BackendEvents.STREAM_ERROR, "connection reset")

        assertEquals("connection reset", seen?.details)
        assertTrue(seen?.fatal == true, "a reported stream error was treated as recoverable")
    }

    @Test
    fun aStreamErrorWithNoDetailIsStillReported() = runTest {
        // An engine that reports the failure without saying what it was is
        // common. Dropping the event because the string is empty would lose the
        // only signal there was.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.setup()
        var fired = false
        player.on(CoreEvents.StreamError) { fired = true }

        backend.fire(BackendEvents.STREAM_ERROR, null)

        assertTrue(fired, "a stream error with no detail was swallowed")
    }
}
