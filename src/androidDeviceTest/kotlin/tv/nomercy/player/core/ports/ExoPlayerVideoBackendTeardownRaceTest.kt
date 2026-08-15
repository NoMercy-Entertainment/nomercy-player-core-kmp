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

// F20, driven for real: a call queued right after release() lands on an
// already-released ExoPlayer, on a real device, through the actual
// fireAndForget scope both routes share — not a mock that never throws.
//
// release() and pause() are both fireAndForget on the SAME main-thread
// CoroutineScope. Called back-to-back from this instrumentation thread, both
// land on that dispatcher's queue in that order — the exact "a seek issued
// right as the player screen tears down" shape the finding describes, and
// close enough to the FIFO case the class's own release() doc says is not
// guaranteed under real contention. If ExoPlayer really does throw
// IllegalStateException on a call to a released player (it does, confirmed
// by Media3's own documented contract), this proves the guard this pass
// added to fireAndForget actually catches it rather than crashing the
// process — the thing ExoPlayerVideoBackendTest's own suite, which never
// calls anything after release(), cannot see.
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

            // release() first, then a burst of calls right behind it — the
            // teardown itself, plus everything a screen tearing down at the
            // same moment might still have in flight.
            backend.release()
            backend.pause()
            backend.currentTime(1.0)
            backend.playbackRate(1.5)

            // The process still running long enough to reach this sleep (and
            // an instrumentation runner still able to report a result
            // afterward) is the actual proof — an uncaught
            // IllegalStateException on any of the three calls above would
            // have taken the whole process down before this line.
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
