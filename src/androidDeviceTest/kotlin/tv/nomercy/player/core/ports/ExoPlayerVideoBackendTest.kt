// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 3
private const val SETTLE_MS = 250L
private const val PLAY_MS = 1_500L

// The Android engine gate, on a real device.
//
// Nothing is mocked. Media3's own test doubles would prove that a double maps
// its own events correctly, which is not the question — the question is whether
// the engine that ships reports what the controllers above expect, in the order
// they expect it.
//
// The media is a silent WAV written by the test. No network, no bundled asset,
// no ffmpeg: a WAV header is forty-four bytes and every Android decoder plays
// one, so the gate runs anywhere the device does.
class ExoPlayerVideoBackendTest {

    // Built per test rather than in a fixture. A fixture that fails leaves
    // every test reporting an uninitialised field from its teardown, which
    // hides the real error — that is how the first run of this gate read.
    private fun withBackend(body: (ExoPlayerVideoBackend, BackendEventRecorder, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val media = File(context.cacheDir, "gate.wav").also { it.writeBytes(silentWav(SECONDS)) }

        // ExoPlayer must be built on the main thread and an instrumentation test
        // runs on a worker. The backend is main-thread-only and says so; where
        // main is, is the caller's business.
        var built: ExoPlayerVideoBackend? = null
        instrumentation.runOnMainSync { built = ExoPlayerVideoBackend(context) }
        val backend: ExoPlayerVideoBackend = requireNotNull(built) { "ExoPlayer was not built on the main thread" }

        try {
            body(backend, BackendEventRecorder(backend), media)
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun aRealEngineReportsTheCanonicalSpineInOrder(): Unit = withBackend { backend, recorder, media ->
        runBlocking {
        backend.load(media.toURI().toString(), LoadOptions())
        Thread.sleep(SETTLE_MS)

        backend.play()
        Thread.sleep(PLAY_MS)
        val whilePlaying: Double = backend.currentTime()

        backend.pause()
        Thread.sleep(SETTLE_MS)

        // The point of the whole spike: one vocabulary, three engines. This is
        // the same assertion the desktop and Apple gates make, against the same
        // ruler.
        assertCanonicalSubsequence(recorder.names(), CanonicalBackendEvent.PLAY_PAUSE_SPINE)
        assertTrue(whilePlaying > 0.0, "the playhead did not move: $whilePlaying")
        }
    }

    @Test
    fun theEngineKnowsHowLongTheMediaIsOnceItHasReadIt(): Unit = withBackend { backend, _, media ->
        runBlocking { backend.load(media.toURI().toString(), LoadOptions()) }
        Thread.sleep(SETTLE_MS)

        // Media3 answers TIME_UNSET before it has read the container, which is a
        // large negative a scrubber would try to divide by.
        assertTrue(backend.duration() > 0.0, "duration was ${backend.duration()}")
    }

    @Test
    fun seekingMovesThePlayheadWhereItWasAsked(): Unit = withBackend { backend, _, media ->
        runBlocking { backend.load(media.toURI().toString(), LoadOptions()) }
        Thread.sleep(SETTLE_MS)

        backend.currentTime(2.0)
        Thread.sleep(SETTLE_MS)

        assertEquals(2.0, backend.currentTime(), absoluteTolerance = 0.5)
    }

    @Test
    fun startingAtAPositionStartsThere(): Unit = withBackend { backend, _, media ->
        runBlocking { backend.load(media.toURI().toString(), LoadOptions(startPositionMs = 1_500L)) }
        Thread.sleep(SETTLE_MS)

        // A resume that started from zero would be the bug nobody reports
        // because they just seek again.
        assertTrue(backend.currentTime() >= 1.0, "resumed at ${backend.currentTime()}")
    }

    @Test
    fun volumeRoundTripsThroughTheZeroToOneContract(): Unit = withBackend { backend, _, _ ->
        backend.volume(0.5f)
        Thread.sleep(SETTLE_MS)

        assertEquals(0.5f, backend.volume(), absoluteTolerance = 0.01f)
    }

    // A WAV of silence: forty-four bytes of header and the rest zeroes. Real
    // media for the decoder, and nothing to download.
    private fun silentWav(seconds: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val dataSize = byteRate * seconds
        val out = java.io.ByteArrayOutputStream()

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun int16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(channels)
        int32(SAMPLE_RATE); int32(byteRate); int16(channels * bitsPerSample / 8); int16(bitsPerSample)
        ascii("data"); int32(dataSize)
        out.write(ByteArray(dataSize))

        return out.toByteArray()
    }
}
