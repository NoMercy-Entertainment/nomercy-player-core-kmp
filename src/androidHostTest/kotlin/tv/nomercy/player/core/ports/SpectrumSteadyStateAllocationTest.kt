// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Test
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A music stream is analyzed for hours, so the steady state may not allocate
// arrays: on a weak TV the garbage collector stalled the audio track.
class SpectrumSteadyStateAllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(): Long = threads.allThreadIds.sumOf { threads.getThreadAllocatedBytes(it) }

    private fun window(): ByteBuffer {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(WINDOW * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until WINDOW) {
            val sample: Short = (sin(2.0 * PI * TONE * frame / RATE) * 8_000).toInt().toShort()
            buffer.putShort(sample)
            buffer.putShort(sample)
        }
        return buffer.also { it.flip() }
    }

    // Feeds one window and waits until its frame reaches the tap, so the
    // count holds whether the analysis runs on the audio thread or beside it.
    private fun feed(processor: BiquadEqAudioProcessor, seen: AtomicInteger, input: ByteBuffer) {
        val before: Int = seen.get()
        input.rewind()
        processor.queueInput(input)
        processor.output
        val deadline: Long = System.nanoTime() + WAIT_NANOS
        while (seen.get() == before && System.nanoTime() < deadline) Thread.sleep(0, SPIN_NANOS)
        assertTrue(seen.get() > before, "no frame arrived for window")
    }

    @Test
    fun analysingForAThousandWindowsAllocatesNoArrays() {
        val processor = BiquadEqAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val seen = AtomicInteger()
        processor.graph().installFrameTap { seen.incrementAndGet() }
        val input: ByteBuffer = window()

        repeat(WARM_UP) { feed(processor, seen, input) }
        val before: Long = allocatedBytes()
        repeat(ROUNDS) { feed(processor, seen, input) }
        val perWindow: Long = (allocatedBytes() - before) / ROUNDS

        assertTrue(perWindow < BUDGET_BYTES, "allocated $perWindow bytes per window, budget $BUDGET_BYTES")
        processor.reset()
    }

    @Test
    fun framesShareTheirArraysSoNothingIsAllocatedPerFrame() {
        val processor = BiquadEqAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val seen = AtomicInteger()
        val frames = CopyOnWriteArrayList<VisualizationFrame>()
        processor.graph().installFrameTap {
            frames += it
            seen.incrementAndGet()
        }
        val input: ByteBuffer = window()

        repeat(WARM_UP) { feed(processor, seen, input) }
        frames.clear()
        repeat(DISTINCT_LIMIT * 4) { feed(processor, seen, input) }

        val distinct: Int = frames.map { System.identityHashCode(it.frequency) }.toSet().size
        assertTrue(distinct <= DISTINCT_LIMIT, "$distinct distinct frequency arrays in ${frames.size} frames")
        assertEquals(frames.size, frames.map { it.sampleRate }.size)
        processor.reset()
    }
}

private const val RATE = 48_000
private const val WINDOW = 4_096
private const val TONE = 440.0
private const val WARM_UP = 20
private const val ROUNDS = 1_000
private const val BUDGET_BYTES = 4_096L
private const val DISTINCT_LIMIT = 3
private const val WAIT_NANOS = 2_000_000_000L
private const val SPIN_NANOS = 200_000
