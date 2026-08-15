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

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 3
private const val SETTLE_MS = 250L

// F20 on real hardware: calls queued right after release() land on an
// already-released ExoPlayer, through the real fireAndForget scope.
class ExoPlayerVideoBackendTeardownRaceTest {

    @Test
    fun aCallQueuedRightAfterReleaseDoesNotCrashTheProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val media = File(context.cacheDir, "teardown-race.wav").also { it.writeBytes(silentWav(SECONDS)) }

        var built: ExoPlayerVideoBackend? = null
        instrumentation.runOnMainSync { built = ExoPlayerVideoBackend(context) }
        val backend: ExoPlayerVideoBackend = requireNotNull(built) { "ExoPlayer was not built on the main thread" }

        try {
            runBlocking { backend.load(media.toURI().toString(), LoadOptions()) }
            Thread.sleep(SETTLE_MS)

            backend.release()
            backend.pause()
            backend.currentTime(1.0)
            backend.playbackRate(1.5)
            Thread.sleep(SETTLE_MS)
        } finally {
            media.delete()
        }
    }

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
