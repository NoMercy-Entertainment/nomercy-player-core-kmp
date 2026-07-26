// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The desktop engine, against the real libVLC.
//
// This is the acceptance gate the spike exists for: a real engine, driven for
// real, measured with the same ruler the other two engines will be. Nothing is
// mocked — a mocked engine proves the mock maps its own events correctly.
//
// It skips when libVLC is absent rather than failing, because a machine without
// VLC installed is not a broken build. The skip is loud: the test that asserts
// availability tells you which case you are in.
class VlcjVideoBackendTest {

    private fun withBackend(body: (VlcjVideoBackend, BackendEventRecorder) -> Unit) {
        if (!VlcjVideoBackend.isAvailable()) {
            println("libVLC not installed — skipping the desktop engine gate")
            return
        }
        val backend = VlcjVideoBackend()
        val recorder = BackendEventRecorder(backend)
        try {
            body(backend, recorder)
        } finally {
            backend.release()
        }
    }

    @Test
    fun askingWhetherLibVlcIsThereAnswersRatherThanThrowing() {
        // The question has to be safe to ask on a machine that does not have it,
        // which is most CI runners. It was not: VLCJ's static initialiser threw
        // ExceptionInInitializerError and this test was the one that found out.
        val reason: String? = VlcjVideoBackend.whyUnavailable()

        println("libVLC: ${reason ?: "available"}")
        assertEquals(reason == null, VlcjVideoBackend.isAvailable())
    }

    @Test
    fun aFreshEngineIsIdleAndReportsNothingYet() = withBackend { backend, recorder ->
        assertEquals(BackendState.IDLE, backend.state())
        assertTrue(recorder.names().isEmpty())
    }

    @Test
    fun anUnknownPositionIsZeroRatherThanNegative() = withBackend { backend, _ ->
        // libVLC answers -1 for "I do not know yet", on time as well as on
        // length. This gate caught it on its first run: a scrubber bound to a
        // negative position draws a bar pointing the wrong way.
        assertEquals(0.0, backend.currentTime())
        assertTrue(backend.duration() >= 0.0)
    }

    @Test
    fun loadingAnnouncesLoadStartBeforeAnythingElse() = withBackend { backend, recorder ->
        kotlinx.coroutines.runBlocking {
            backend.load("file:///nonexistent-on-purpose.mp4", LoadOptions())
        }

        // The first thing every engine must say, whatever happens next. This one
        // is asserted without real media because it is the one event that does
        // not depend on the file existing.
        assertEquals(CanonicalBackendEvent.LOAD_START, recorder.names().first())
    }

    @Test
    fun volumeRoundTripsThroughTheZeroToOneContract() = withBackend { backend, _ ->
        backend.volume(0.5f)

        // The contract is 0..1 and libVLC's is 0..100. The conversion happens in
        // one place and this is what proves it happens in both directions.
        assertTrue(backend.volume() in 0.4f..0.6f)
    }

    @Test
    fun playbackRateRoundTrips() = withBackend { backend, _ ->
        backend.playbackRate(1.5)

        assertEquals(1.5, backend.playbackRate(), absoluteTolerance = 0.01)
    }

    @Test
    fun durationIsZeroRatherThanNegativeBeforeTheContainerIsRead() = withBackend { backend, _ ->
        // libVLC reports -1 until it has read the container, and a scrubber
        // dividing by that produces a bar pointing the wrong way.
        assertTrue(backend.duration() >= 0.0)
    }

    @Test
    fun mutingAndUnmutingDoNotThrowOnAnEngineWithNoMedia() = withBackend { backend, _ ->
        // A chrome binds mute before anything is loaded and this is where an
        // engine that assumed media would throw.
        backend.mute()
        backend.unmute()

        assertEquals(BackendState.IDLE, backend.state())
    }
}
