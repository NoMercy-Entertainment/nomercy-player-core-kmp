// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nomercy.player.core.dsp.BiquadCoefficients
import tv.nomercy.player.core.dsp.BiquadPeaking
import tv.nomercy.player.core.dsp.BiquadState
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.SpectrumAnalyzer
import tv.nomercy.player.core.dsp.SpectrumHistory
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

// The equaliser and the spectrum, as a stage in Media3's audio sink.
//
// Not android.media.audiofx.Equalizer, and the reason is testability rather
// than preference. The platform equaliser needs a live audio session id, which
// means a device, an actual playing stream and a system service; its Visualizer
// counterpart is device-only with no Robolectric shadow at all. This is a
// ByteBuffer in and a ByteBuffer out, so a synthetic sine can be pushed through
// it on a workstation and the boost measured by FFT. The platform path stays
// available as the on-device parity check; this is the one with a ruler.
//
// It does both jobs in one pass because both need the same samples decoded, and
// decoding PCM16 twice per buffer to keep two stages separate would be paying
// twice for the expensive part.
@OptIn(UnstableApi::class)
public class BiquadEqAudioProcessor(
    // Shared with whatever is drawing, so `SpectrumPlugin.smoothingTimeConstant`
    // reaches Media3's sink without a new method on the port. A host that does
    // not care passes nothing and gets the web's default.
    private val history: SpectrumHistory = SpectrumHistory(),
) : BaseAudioProcessor() {

    // Per channel, per band. A biquad carries two samples of history and the
    // channels are independent signals — sharing state across them mixes left
    // into right, which is audible as a collapsed stereo image rather than as
    // an obviously broken filter.
    private var states: Array<Array<BiquadState>> = emptyArray()

    private var coefficients: List<BiquadCoefficients> = emptyList()

    private var bands: List<EqBand> = EqBands.DEFAULT

    private var preGain: Double = 1.0

    private var enabled: Boolean = true

    @Volatile
    private var sampleRate: Int = 0

    private var channels: Int = 0

    // Samples accumulate here until there are enough for a transform. An FFT
    // needs a power of two and a decoder hands over whatever the container
    // happened to hold, so the two never line up on their own. Two windows: the
    // audio thread fills one while the spectrum thread reads the other.
    private val windows: Array<DoubleArray> = arrayOf(DoubleArray(FFT_SIZE), DoubleArray(FFT_SIZE))

    private var writing: Int = 0

    private var windowFill: Int = 0

    @Volatile
    private var frameTap: ((VisualizationFrame) -> Unit)? = null

    private var elapsedSeconds: Double = 0.0

    // Index of the window waiting for analysis, or NO_WINDOW. The audio thread
    // sets it, the spectrum thread clears it when the frame is delivered.
    private val ready = AtomicInteger(NO_WINDOW)

    @Volatile
    private var readyElapsedSeconds: Double = 0.0

    @Volatile
    private var resetPending: Boolean = false

    private val analyzer = SpectrumAnalyzer(FFT_SIZE)

    private var worker: SpectrumWorker? = null

    public fun graph(): AudioDspGraph = Graph()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // PCM16 only. Media3 will hand over float or 24-bit if something
        // upstream produces it, and silently passing those through unfiltered
        // would be an equaliser that stops working on certain files with no
        // indication why.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        rebuild()
        return inputAudioFormat
    }

    override fun onFlush() {
        // A seek makes the next buffer discontinuous with the last, and a
        // filter carrying history across that discontinuity rings — an audible
        // click at every seek.
        resetStates()
        windowFill = 0
        elapsedSeconds = 0.0
        // The spectrum thread owns the history while it runs, so it applies the reset.
        resetPending = true
    }

    override fun onReset() {
        worker?.stopNow()
        worker = null
        ready.set(NO_WINDOW)
        history.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames: Int = inputBuffer.remaining() / (BYTES_PER_SAMPLE * channels.coerceAtLeast(1))
        val output: ByteBuffer = replaceOutputBuffer(frames * BYTES_PER_SAMPLE * channels)
        val input: ByteBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        repeat(frames) {
            var monoSum = 0.0
            for (channel in 0 until channels) {
                val raw: Double = input.short.toDouble() / PCM16_SCALE
                val shaped: Double = if (enabled) filter(raw, channel) else raw
                monoSum += shaped
                output.putShort(toPcm16(shaped))
            }
            accumulate(monoSum / channels)
        }

        input.position(input.limit())
        output.flip()
    }

    // Every band in series, then the headroom. In that order because the bands
    // are what can push the signal past full scale, and applying headroom first
    // would leave the clipping it exists to prevent.
    private fun filter(sample: Double, channel: Int): Double {
        var value: Double = sample
        for (band in coefficients.indices) {
            // step, not process. The array form makes a fresh state per call,
            // which would restart every filter on every buffer — a click at the
            // seam between each one, forty times a second.
            value = states[channel][band].step(coefficients[band], value)
        }
        return value * preGain
    }

    // Clamped, because a boosted band genuinely can exceed full scale and the
    // wrap-around from a bare conversion is not quiet distortion — it is the
    // loudest possible sound the format can make.
    private fun toPcm16(sample: Double): Short {
        val scaled: Double = sample * PCM16_SCALE
        return scaled.coerceIn(PCM16_MIN, PCM16_MAX).toInt().toShort()
    }

    // Mono, because a spectrum is a picture of the music rather than of one
    // speaker, and running two transforms to average the answers costs twice
    // as much to show the same thing.
    private fun accumulate(sample: Double) {
        windows[writing][windowFill] = sample
        windowFill++
        if (windowFill < FFT_SIZE) return

        windowFill = 0
        if (frameTap == null) return
        elapsedSeconds += FFT_SIZE.toDouble() / sampleRate

        // The transform runs on its own low-priority thread: on a weak TV it
        // starved the audio track. A window arriving while the last one is still
        // being analyzed is dropped; the display catches up on the next.
        if (ready.get() != NO_WINDOW) return

        readyElapsedSeconds = elapsedSeconds
        ready.set(writing)
        writing = 1 - writing
        (worker ?: SpectrumWorker(::analyzeReady).also { worker = it; it.start() }).wake()
    }

    // False when nothing waits, so the worker parks.
    private fun analyzeReady(): Boolean {
        val index: Int = ready.get()
        if (index == NO_WINDOW) return false

        if (resetPending) {
            resetPending = false
            history.reset()
        }
        try {
            val tap: ((VisualizationFrame) -> Unit)? = frameTap
            if (tap != null) {
                val deltaMs: Double = FFT_SIZE * MILLIS_PER_SECOND / sampleRate
                // A throwing listener must not end the thread that feeds every other one.
                runCatching {
                    tap(analyzer.analyze(windows[index], sampleRate, deltaMs, readyElapsedSeconds, history))
                }
            }
        } finally {
            ready.set(NO_WINDOW)
        }
        return true
    }

    private fun rebuild() {
        if (sampleRate <= 0 || channels <= 0) return

        coefficients = bands.map { band ->
            BiquadPeaking.coefficients(
                frequencyHz = band.frequency.toDouble(),
                gainDb = band.gainDb,
                bandwidth = band.bandwidth,
                sampleRate = sampleRate,
            )
        }
        resetStates()
    }

    private fun resetStates() {
        if (channels <= 0) return

        states = Array(channels) { Array(bands.size) { BiquadState() } }
    }

    // Recomputed in place, leaving the filter state alone.
    //
    // A slider drag arrives many times a second. Rebuilding the chain would
    // reset the history on each one, and a filter restarted mid-signal clicks —
    // which is the difference between a slider that sounds smooth and one that
    // crackles all the way along.
    private fun retune(index: Int, gainDb: Double) {
        val band: EqBand = bands[index].copy(gainDb = gainDb)
        bands = bands.toMutableList().also { it[index] = band }
        if (sampleRate <= 0) return

        coefficients = coefficients.toMutableList().also {
            it[index] = BiquadPeaking.coefficients(
                frequencyHz = band.frequency.toDouble(),
                gainDb = gainDb,
                bandwidth = band.bandwidth,
                sampleRate = sampleRate,
            )
        }
    }

    private inner class Graph : AudioDspGraph {
        override fun setEqBands(bands: List<EqBand>) {
            this@BiquadEqAudioProcessor.bands = bands
            rebuild()
        }

        override fun bandGain(frequencyHz: Int, gainDb: Double) {
            val index: Int = bands.indexOfFirst { it.frequency == frequencyHz }
            if (index < 0) return

            retune(index, gainDb)
        }

        override fun preGain(linear: Double) {
            preGain = linear
        }

        override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription {
            frameTap = onFrame
            return Subscription { if (frameTap === onFrame) frameTap = null }
        }

        override fun removeFrameTap() {
            frameTap = null
        }

        override fun eqEnabled(enabled: Boolean) {
            this@BiquadEqAudioProcessor.enabled = enabled
        }
    }
}

// The transform's own thread, at the lowest priority so the audio and video
// threads always win a busy CPU. Parks when no window waits.
private class SpectrumWorker(private val step: () -> Boolean) : Thread("nm-spectrum") {
    @Volatile
    private var running = true

    init {
        isDaemon = true
        priority = MIN_PRIORITY
    }

    override fun run() {
        while (running) {
            if (!step()) LockSupport.park(this)
        }
    }

    fun wake() = LockSupport.unpark(this)

    fun stopNow() {
        running = false
        LockSupport.unpark(this)
    }
}

private const val NO_WINDOW = -1

private const val BYTES_PER_SAMPLE = 2

// Full scale for signed 16-bit. The negative side reaches one further than the
// positive, which is why the clamp names both ends rather than one magnitude.
private const val PCM16_SCALE = 32_768.0
private const val PCM16_MIN = -32_768.0
private const val PCM16_MAX = 32_767.0

// About 85ms at 48kHz — slow enough that the transform is cheap and fast enough
// that a visualiser does not lag the music visibly.
private const val FFT_SIZE = 4_096

private const val MILLIS_PER_SECOND = 1_000.0
