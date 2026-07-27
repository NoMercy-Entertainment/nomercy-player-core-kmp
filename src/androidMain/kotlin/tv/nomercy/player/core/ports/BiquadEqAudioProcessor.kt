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
import tv.nomercy.player.core.dsp.AudioSpectrum
import tv.nomercy.player.core.dsp.BiquadCoefficients
import tv.nomercy.player.core.dsp.BiquadPeaking
import tv.nomercy.player.core.dsp.BiquadState
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.BandEnergies
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
public class BiquadEqAudioProcessor : BaseAudioProcessor() {

    // Per channel, per band. A biquad carries two samples of history and the
    // channels are independent signals — sharing state across them mixes left
    // into right, which is audible as a collapsed stereo image rather than as
    // an obviously broken filter.
    private var states: Array<Array<BiquadState>> = emptyArray()

    private var coefficients: List<BiquadCoefficients> = emptyList()

    private var bands: List<EqBand> = EqBands.DEFAULT

    private var preGain: Double = 1.0

    private var enabled: Boolean = true

    private var sampleRate: Int = 0

    private var channels: Int = 0

    // Samples accumulate here until there are enough for a transform. An FFT
    // needs a power of two and a decoder hands over whatever the container
    // happened to hold, so the two never line up on their own.
    private var window: DoubleArray = DoubleArray(FFT_SIZE)

    private var windowFill: Int = 0

    private var frameTap: ((VisualizationFrame) -> Unit)? = null

    private var previousPeaks: BandEnergies? = null

    private var elapsedSeconds: Double = 0.0

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
        previousPeaks = null
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
        window[windowFill] = sample
        windowFill++
        if (windowFill < FFT_SIZE) return

        windowFill = 0
        val tap: ((VisualizationFrame) -> Unit) = frameTap ?: return
        val deltaMs: Double = FFT_SIZE * MILLIS_PER_SECOND / sampleRate
        elapsedSeconds += deltaMs / MILLIS_PER_SECOND

        val frame: VisualizationFrame =
            AudioSpectrum.analyse(window.copyOf(), sampleRate, deltaMs, elapsedSeconds, previousPeaks)
        previousPeaks = frame.peakBandEnergies
        tap(frame)
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
