// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// AUD-3034: stop() must not free the pulse stream while the capture thread is
// still inside a blocked read.
class PulseAudioLoopbackCaptureStopTest {

    private class BlockingStream : PulseStream {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val freed = CountDownLatch(1)
        val freeCount = AtomicInteger()
        val readInFlight = AtomicBoolean(false)
        val freedWhileReading = AtomicBoolean(false)

        override fun read(buffer: FloatArray): Boolean {
            readInFlight.set(true)
            readEntered.countDown()
            releaseRead.await()
            readInFlight.set(false)
            return true
        }

        override fun free() {
            if (readInFlight.get()) freedWhileReading.set(true)
            freeCount.incrementAndGet()
            freed.countDown()
        }
    }

    @Test
    fun stopDoesNotFreeTheStreamWhileTheCaptureThreadIsStillReading() {
        val stream = BlockingStream()
        val capture = PulseAudioLoopbackCapture({ _, _ -> stream }, joinTimeoutMs = 50)

        assertTrue(capture.start(48_000, 2) { _, _ -> })
        assertTrue(stream.readEntered.await(5, TimeUnit.SECONDS))

        capture.stop()

        assertFalse(stream.freedWhileReading.get(), "stream was freed while a read was in flight")
        assertEquals(0, stream.freeCount.get(), "stream was freed before the capture thread ended")

        stream.releaseRead.countDown()
        assertTrue(stream.freed.await(5, TimeUnit.SECONDS), "stream was never freed after the thread ended")
        assertEquals(1, stream.freeCount.get())
    }

    @Test
    fun stopFreesTheStreamExactlyOnceWhenTheThreadEndsInTime() {
        val stream = BlockingStream()
        stream.releaseRead.countDown()
        val capture = PulseAudioLoopbackCapture({ _, _ -> stream }, joinTimeoutMs = 5_000)

        assertTrue(capture.start(48_000, 2) { _, _ -> })
        assertTrue(stream.readEntered.await(5, TimeUnit.SECONDS))
        capture.stop()

        assertTrue(stream.freed.await(5, TimeUnit.SECONDS))
        assertEquals(1, stream.freeCount.get())
    }
}
