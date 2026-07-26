// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 3

// The Apple engine gate, against real AVFoundation.
//
// Nothing is stubbed. AVFoundation's behaviour around indefinite CMTime, its
// status properties and its periodic observer are exactly the things a stub
// would get wrong in the same way the code does, which is why the gate drives
// the real one.
//
// The media is a silent WAV written to the temp directory: forty-four bytes of
// header and the rest zeroes. AVFoundation plays WAV, so the gate needs no
// network and no bundled asset.
@OptIn(ExperimentalForeignApi::class)
class AVPlayerVideoBackendTest {

    private fun withBackend(body: (AVPlayerVideoBackend, BackendEventRecorder, String) -> Unit) {
        val path = "${NSTemporaryDirectory()}nomercy-gate.wav"
        writeSilentWav(path, SECONDS)

        val backend = AVPlayerVideoBackend()
        try {
            body(backend, BackendEventRecorder(backend), "file://$path")
        } finally {
            backend.release()
        }
    }

    @Test
    fun aFreshEngineReportsZeroRatherThanNaN() = withBackend { backend, _, _ ->
        // CMTime is indefinite before an asset is read and CMTimeGetSeconds
        // gives NaN for it. NaN reaching a scrubber draws nothing and explains
        // nothing.
        assertEquals(0.0, backend.currentTime())
        assertEquals(0.0, backend.duration())
        assertEquals(BackendState.IDLE, backend.state())
    }

    @Test
    fun loadingAnnouncesLoadStartFirst() = withBackend { backend, recorder, url ->
        kotlinx.coroutines.runBlocking { backend.load(url, LoadOptions()) }

        assertEquals(CanonicalBackendEvent.LOAD_START, recorder.names().first())
    }

    @Test
    fun volumeRoundTripsThroughTheZeroToOneContract() = withBackend { backend, _, _ ->
        backend.volume(0.5f)

        assertTrue(backend.volume() in 0.4f..0.6f)
    }

    @Test
    fun playbackRateRoundTrips() = withBackend { backend, _, _ ->
        backend.playbackRate(1.5)

        assertEquals(1.5, backend.playbackRate(), absoluteTolerance = 0.01)
    }

    @Test
    fun mutingDoesNotThrowBeforeThereIsAnything() = withBackend { backend, _, _ ->
        backend.mute()
        backend.unmute()

        assertEquals(BackendState.IDLE, backend.state())
    }

    // Forty-four bytes of header and the rest zeroes. Real media for the
    // decoder, and nothing to download.
    private fun writeSilentWav(path: String, seconds: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val dataSize = byteRate * seconds
        val bytes = ArrayList<Byte>(44 + dataSize)

        fun ascii(text: String) = text.forEach { bytes.add(it.code.toByte()) }
        fun int32(value: Int) = repeat(4) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }
        fun int16(value: Int) = repeat(2) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }

        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(channels)
        int32(SAMPLE_RATE); int32(byteRate); int16(channels * bitsPerSample / 8); int16(bitsPerSample)
        ascii("data"); int32(dataSize)
        repeat(dataSize) { bytes.add(0) }

        val array: ByteArray = bytes.toByteArray()
        array.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = array.size.toULong())
                .writeToFile(path, atomically = true)
        }
    }
}
