// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import tv.nomercy.player.core.plugins.audio.BandEnergies
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The reduction of AudioSpectrum with every buffer made once, for a stream that
// is analyzed for hours. One analyzer per FFT size, driven from one thread. A
// frame's arrays are overwritten OUTPUT_RING frames later, so a consumer that
// keeps a frame longer copies the arrays first.
internal class SpectrumAnalyzer(private val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val half: Int = size / 2
    private val hann = DoubleArray(size) { index -> HALF * (1.0 - cos(FULL_TURN * index / (size - 1))) }
    private val cosines = DoubleArray(half) { cos(-FULL_TURN * it / size) }
    private val sines = DoubleArray(half) { sin(-FULL_TURN * it / size) }
    private val windowed = DoubleArray(size)
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val frequencies = Array(OUTPUT_RING) { DoubleArray(half) }
    private val waveforms = Array(OUTPUT_RING) { DoubleArray(size) }
    private var slot = 0

    @Suppress("LongParameterList")
    fun analyze(
        pcm: DoubleArray,
        sampleRate: Int,
        deltaMs: Double,
        timeSeconds: Double,
        history: SpectrumHistory?,
    ): VisualizationFrame {
        require(pcm.size == size) { "Expected $size samples, got ${pcm.size}" }

        for (index in 0 until size) windowed[index] = pcm[index] * hann[index]
        val binHz: Double = sampleRate.toDouble() / size

        windowed.copyInto(real)
        imaginary.fill(0.0)
        transform()

        val frequency: DoubleArray = frequencies[slot]
        val waveform: DoubleArray = waveforms[slot]
        slot = (slot + 1) % OUTPUT_RING

        // Divided by the window size first, matching Web Audio's own normalization.
        for (bin in 0 until half) frequency[bin] = hypot(real[bin], imaginary[bin]) / size

        val steadied: DoubleArray = history?.smoothInPlace(frequency) ?: frequency
        toDecibelScale(steadied, frequency)
        normalize(windowed, waveform)

        val bands = BandEnergies(
            bass = AudioSpectrum.bandEnergy(frequency, binHz, BASS_LOW_HZ, BASS_HIGH_HZ),
            mid = AudioSpectrum.bandEnergy(frequency, binHz, MID_LOW_HZ, MID_HIGH_HZ),
            treble = AudioSpectrum.bandEnergy(frequency, binHz, TREBLE_LOW_HZ, TREBLE_HIGH_HZ),
        )
        val previous: BandEnergies? = history?.peaks
        val peaks: BandEnergies = if (previous == null) bands else held(previous, bands)
        history?.peaks = peaks

        return VisualizationFrame(
            frequency = frequency,
            waveform = waveform,
            time = timeSeconds,
            deltaMs = deltaMs,
            energy = (bands.bass + bands.mid + bands.treble) / BAND_COUNT,
            bandEnergies = bands,
            sampleRate = sampleRate,
            binHz = binHz,
            peakHz = peakFrequency(frequency, binHz),
            peakBandEnergies = peaks,
        )
    }

    // Radix-2 in place over the preallocated pair, twiddles from the tables:
    // computing cos and sin per butterfly was ~24k trig calls per window.
    private fun transform() {
        reverseBits()
        var span = 2
        while (span <= size) {
            var start = 0
            while (start < size) {
                butterfly(start, span)
                start += span
            }
            span = span shl 1
        }
    }

    private fun butterfly(start: Int, span: Int) {
        val halfSpan: Int = span / 2
        val stride: Int = size / span
        for (offset in 0 until halfSpan) {
            val cosine: Double = cosines[offset * stride]
            val sine: Double = sines[offset * stride]
            val here: Int = start + offset
            val there: Int = here + halfSpan
            val realPart: Double = real[there] * cosine - imaginary[there] * sine
            val imaginaryPart: Double = real[there] * sine + imaginary[there] * cosine
            real[there] = real[here] - realPart
            imaginary[there] = imaginary[here] - imaginaryPart
            real[here] += realPart
            imaginary[here] += imaginaryPart
        }
    }

    private fun reverseBits() {
        var target = 0
        for (index in 0 until size - 1) {
            if (index < target) {
                val heldReal: Double = real[index]
                real[index] = real[target]
                real[target] = heldReal
                val heldImaginary: Double = imaginary[index]
                imaginary[index] = imaginary[target]
                imaginary[target] = heldImaginary
            }
            var mask: Int = size shr 1
            while (target and mask != 0) {
                target = target and mask.inv()
                mask = mask shr 1
            }
            target = target or mask
        }
    }

    // Silence is zero rather than negative infinity, so no NaN reaches a consumer.
    private fun toDecibelScale(magnitudes: DoubleArray, out: DoubleArray) {
        val span: Double = MAX_DECIBELS - MIN_DECIBELS
        for (bin in 0 until half) {
            val scaled: Double = magnitudes[bin]
            out[bin] = if (scaled <= 0.0) {
                0.0
            } else {
                ((DECIBEL_FACTOR * log10(scaled) - MIN_DECIBELS) / span).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun normalize(samples: DoubleArray, out: DoubleArray) {
        var loudest = 0.0
        for (sample in samples) {
            val magnitude: Double = if (sample < 0.0) -sample else sample
            if (magnitude > loudest) loudest = magnitude
        }
        if (loudest <= 0.0) {
            out.fill(0.0)
            return
        }
        for (index in samples.indices) out[index] = samples[index] / loudest
    }

    private fun peakFrequency(magnitudes: DoubleArray, binHz: Double): Double {
        var loudestBin = 0
        var loudest = 0.0
        for (bin in 0 until half) {
            if (magnitudes[bin] > loudest) {
                loudest = magnitudes[bin]
                loudestBin = bin
            }
        }
        return loudestBin * binHz
    }

    private fun held(previous: BandEnergies, now: BandEnergies): BandEnergies = BandEnergies(
        bass = maxOf(now.bass, previous.bass - PEAK_DECAY),
        mid = maxOf(now.mid, previous.mid - PEAK_DECAY),
        treble = maxOf(now.treble, previous.treble - PEAK_DECAY),
    )

    companion object {
        const val OUTPUT_RING: Int = 3

        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI
        private const val BASS_LOW_HZ = 20.0
        private const val BASS_HIGH_HZ = 250.0
        private const val MID_LOW_HZ = 250.0
        private const val MID_HIGH_HZ = 4_000.0
        private const val TREBLE_LOW_HZ = 4_000.0
        private const val TREBLE_HIGH_HZ = 16_000.0
        private const val BAND_COUNT = 3.0
        private const val PEAK_DECAY = 0.003
        private const val MIN_DECIBELS = -100.0
        private const val MAX_DECIBELS = -30.0
        private const val DECIBEL_FACTOR = 20.0
    }
}
