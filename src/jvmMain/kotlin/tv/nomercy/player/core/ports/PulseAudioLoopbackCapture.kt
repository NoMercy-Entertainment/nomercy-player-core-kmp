// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

// JNA resolves a native function by its symbol NAME, so the bindings below have
// to spell the PulseAudio symbols exactly — pa_simple_new, not paSimpleNew.
// Renaming them to satisfy Kotlin's convention would stop them resolving at
// runtime.
// pa_simple_new's nine arguments are PulseAudio's signature, not a shape this
// binding chose, so LongParameterList is a property of the C API here.
@file:Suppress("FunctionNaming", "LongParameterList")

package tv.nomercy.player.core.ports

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.atomic.AtomicBoolean

// Linux loopback via PulseAudio's monitor source — every sink PulseAudio (and
// PipeWire's Pulse-compatible layer, which every current desktop Linux ships)
// creates has a matching `<sink>.monitor` source carrying everything sent to
// it, which is exactly the "whatever is playing right now" signal this needs.
//
// `pa_simple` rather than the full async `pa_context`/mainloop API: it is a
// blocking convenience wrapper built for exactly this — one stream, one
// direction, read in a loop — and the full API's callback/mainloop plumbing
// buys nothing here that pa_simple does not already give for free.
internal class PulseAudioLoopbackCapture(
    private val opener: PulseStreamOpener = NativePulseStreamOpener,
    private val joinTimeoutMs: Long = THREAD_JOIN_TIMEOUT_MS,
) : AudioLoopbackCapture {

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        if (running.get()) return true

        val handle = opener.open(sampleRate, channels) ?: return false

        running.set(true)

        // A dedicated thread, not a coroutine — pa_simple_read blocks the
        // calling thread on the underlying socket for as long as it takes
        // PulseAudio to have a full buffer ready, which is not work a
        // coroutine dispatcher should have parked on it.
        captureThread = Thread({
            val buffer = FloatArray(FRAMES_PER_READ * channels)
            try {
                while (running.get()) {
                    if (!handle.read(buffer)) break
                    onFrame(buffer, FRAMES_PER_READ)
                }
            } finally {
                // Only this thread reads the stream, so only it may free it.
                handle.free()
            }
        }, "nomercy-pulse-loopback").apply {
            isDaemon = true
            start()
        }

        return true
    }

    override fun stop() {
        running.set(false)
        // A read still blocked after the timeout frees the stream itself on exit.
        captureThread?.join(joinTimeoutMs)
        captureThread = null
    }
}

// The seam between the capture loop and libpulse, so the stop ordering can be
// tested without a PulseAudio server.
internal interface PulseStream {
    // False when the read failed and the loop must end.
    fun read(buffer: FloatArray): Boolean
    fun free()
}

internal fun interface PulseStreamOpener {
    fun open(sampleRate: Int, channels: Int): PulseStream?
}

// Small enough that a frame is fresh when it reaches PcmEqualiser,
// large enough that this is not a syscall per handful of samples.
private const val FRAMES_PER_READ = 1024
private const val THREAD_JOIN_TIMEOUT_MS = 1_000L
private const val STREAM_NAME = "NoMercyPlayer"
private const val PA_STREAM_RECORD = 2
private const val PA_SAMPLE_FLOAT32LE = 5

private object NativePulseStreamOpener : PulseStreamOpener {
    override fun open(sampleRate: Int, channels: Int): PulseStream? {
        val pulse = runCatching { Native.load("pulse-simple", PulseSimple::class.java) }.getOrNull() ?: return null
        val handle = openMonitor(pulse, sampleRate, channels) ?: return null
        return NativePulseStream(pulse, handle, channels)
    }

    private fun openMonitor(pulse: PulseSimple, sampleRate: Int, channels: Int): Pointer? {
        val monitorSource = defaultMonitorSourceName() ?: return null
        val spec = PaSampleSpec().apply {
            format = PA_SAMPLE_FLOAT32LE
            rate = sampleRate
            this.channels = channels.toByte()
        }
        return pulse.pa_simple_new(
            null, STREAM_NAME, PA_STREAM_RECORD, monitorSource, STREAM_NAME, spec, null, null, IntByReference(),
        )
    }

    // `pactl` rather than the async API: it needs a mainloop thread for a
    // one-shot query. The sink's real name resolves where `@DEFAULT_SINK@`
    // does not.
    private fun defaultMonitorSourceName(): String? = runCatching {
        val process = ProcessBuilder("pactl", "get-default-sink").redirectErrorStream(true).start()
        val sinkName = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (sinkName.isEmpty()) null else "$sinkName.monitor"
    }.getOrNull()
}

private class NativePulseStream(
    private val pulse: PulseSimple,
    private val handle: Pointer,
    channels: Int,
) : PulseStream {
    private val bytes = com.sun.jna.Memory((FRAMES_PER_READ * channels * Float.SIZE_BYTES).toLong())

    override fun read(buffer: FloatArray): Boolean {
        val result = pulse.pa_simple_read(handle, bytes, bytes.size(), IntByReference())
        if (result < 0) return false
        bytes.read(0, buffer, 0, buffer.size)
        return true
    }

    override fun free() = pulse.pa_simple_free(handle)
}

@Structure.FieldOrder("format", "rate", "channels")
private class PaSampleSpec : Structure() {
    @JvmField var format: Int = 0
    @JvmField var rate: Int = 0
    @JvmField var channels: Byte = 0
}

private interface PulseSimple : Library {
    fun pa_simple_new(
        server: String?,
        name: String,
        dir: Int,
        dev: String?,
        streamName: String,
        ss: PaSampleSpec,
        map: Pointer?,
        attr: Pointer?,
        error: IntByReference,
    ): Pointer?

    fun pa_simple_read(stream: Pointer, data: Pointer, bytes: Long, error: IntByReference): Int
    fun pa_simple_free(stream: Pointer)
}
