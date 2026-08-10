// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

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
internal class PulseAudioLoopbackCapture : AudioLoopbackCapture {

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var stream: Pointer? = null

    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        if (running.get()) return true

        val pulse = runCatching { Native.load("pulse-simple", PulseSimple::class.java) }.getOrNull() ?: return false
        val monitorSource = defaultMonitorSourceName() ?: return false

        val spec = PaSampleSpec().apply {
            format = PA_SAMPLE_FLOAT32LE
            rate = sampleRate
            this.channels = channels.toByte()
        }
        val error = IntByReference()
        val handle = pulse.pa_simple_new(
            null, STREAM_NAME, PA_STREAM_RECORD, monitorSource, STREAM_NAME, spec, null, null, error,
        )
        if (handle == null) return false

        stream = handle
        running.set(true)

        // A dedicated thread, not a coroutine — pa_simple_read blocks the
        // calling thread on the underlying socket for as long as it takes
        // PulseAudio to have a full buffer ready, which is not work a
        // coroutine dispatcher should have parked on it.
        captureThread = Thread({
            // Half a spectrum analysis window per read: small enough that a
            // frame is fresh when it reaches PcmEqualiser, large enough that
            // this is not a syscall per handful of samples.
            val framesPerRead = 1024
            val buffer = FloatArray(framesPerRead * channels)
            val byteBuffer = com.sun.jna.Memory((framesPerRead * channels * Float.SIZE_BYTES).toLong())

            while (running.get()) {
                val readError = IntByReference()
                val result = pulse.pa_simple_read(handle, byteBuffer, byteBuffer.size(), readError)
                if (result < 0) break
                byteBuffer.read(0, buffer, 0, buffer.size)
                onFrame(buffer, framesPerRead)
            }
        }, "nomercy-pulse-loopback").apply {
            isDaemon = true
            start()
        }

        return true
    }

    override fun stop() {
        running.set(false)
        captureThread?.join(THREAD_JOIN_TIMEOUT_MS)
        captureThread = null
        stream?.let { PULSE_SIMPLE_FREE?.invoke(it) }
        stream = null
    }

    // `pactl` rather than binding the full context/mainloop API just to ask
    // one question: the async API needs a running mainloop thread for a
    // single-shot "what is the default sink" query, and pa_simple's own
    // surface has no equivalent call. `@DEFAULT_SINK@.monitor` is documented
    // PulseAudio special-case syntax but does not reliably resolve through
    // every pa_simple build encountered in the wild — the sink's real name
    // does.
    private fun defaultMonitorSourceName(): String? = runCatching {
        val process = ProcessBuilder("pactl", "get-default-sink").redirectErrorStream(true).start()
        val sinkName = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (sinkName.isEmpty()) null else "$sinkName.monitor"
    }.getOrNull()

    private companion object {
        const val STREAM_NAME = "NoMercyPlayer"
        const val PA_STREAM_RECORD = 2
        const val PA_SAMPLE_FLOAT32LE = 5
        const val THREAD_JOIN_TIMEOUT_MS = 1_000L

        // Resolved lazily and held apart from the instance-scoped library
        // handle only so [stop] can free the stream without re-loading the
        // library on the way out.
        val PULSE_SIMPLE_FREE: ((Pointer) -> Unit)? = runCatching {
            val lib = Native.load("pulse-simple", PulseSimple::class.java)
            lib::pa_simple_free
        }.getOrNull()
    }
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

    fun pa_simple_read(s: Pointer, data: Pointer, bytes: Long, error: IntByReference): Int
    fun pa_simple_free(s: Pointer)
}
