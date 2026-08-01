// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.math.log10
import tv.nomercy.player.core.plugins.audio.BandEnergies
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// A block of samples turned into the frame a visualiser draws.
//
// Everything a consumer needs is computed once, here, rather than left as a
// spectrum for each visualiser to reduce itself. The three band edges are the
// obvious thing to get slightly different in three places, and three visualisers
// that disagree about where bass ends look like three bugs.
//
// Pure arithmetic on Double, with no engine and no platform in it, so the same
// numbers come out on a phone, a television and a desktop. A visualiser that
// pulsed differently per platform would be the kind of difference nobody can
// reproduce.
public object AudioSpectrum {

    // The whole reduction, in the order the web player does it: window, then
    // transform, then steady against the last frame, then normalise, then split
    // into bands.
    //
    // history is what the previous frame left behind — the peak hold, which is
    // how the peak line decays smoothly rather than tracking the bars exactly,
    // and the smoothed magnitudes the browser's analyser keeps internally. Null
    // starts fresh and smooths nothing; the first frame after a track change has
    // no history worth keeping.
    //
    // Five parameters, and none of them can be derived from the others: the
    // samples, the rate they were taken at, where in the track they are, how
    // long since the last frame, and what the last frame left. Bundling them
    // into a context object would satisfy the rule by moving the same five
    // values one level down and giving callers a type to construct first.
    @Suppress("LongParameterList")
    public fun analyse(
        pcm: DoubleArray,
        sampleRate: Int,
        deltaMs: Double,
        timeSeconds: Double,
        history: SpectrumHistory?,
    ): VisualizationFrame {
        val windowed: DoubleArray = HannWindow.apply(pcm)
        val binHz: Double = sampleRate.toDouble() / pcm.size

        // Divided by the window size first, matching Web Audio's own
        // normalisation, so the numbers mean the same thing at any FFT size —
        // and so the smoothing below blends comparable frames when the size
        // changes under it.
        val scaled: DoubleArray = Fft.magnitudes(windowed).also { magnitudes ->
            for (bin in magnitudes.indices) magnitudes[bin] = magnitudes[bin] / pcm.size
        }

        val steadied: DoubleArray = history?.smooth(scaled) ?: scaled
        val normalised: DoubleArray = toDecibelScale(steadied)

        val bands = BandEnergies(
            bass = bandEnergy(normalised, binHz, BASS_LOW_HZ, BASS_HIGH_HZ),
            mid = bandEnergy(normalised, binHz, MID_LOW_HZ, MID_HIGH_HZ),
            treble = bandEnergy(normalised, binHz, TREBLE_LOW_HZ, TREBLE_HIGH_HZ),
        )

        val peaks: BandEnergies = held(history?.peaks, bands)
        history?.peaks = peaks

        return VisualizationFrame(
            frequency = normalised,
            waveform = normalise(windowed),
            time = timeSeconds,
            deltaMs = deltaMs,
            energy = (bands.bass + bands.mid + bands.treble) / BAND_COUNT,
            bandEnergies = bands,
            sampleRate = sampleRate,
            binHz = binHz,
            peakHz = peakFrequency(normalised, binHz),
            peakBandEnergies = peaks,
        )
    }

    // Decibels, not raw magnitude, and this is the difference between a
    // visualiser that matches the web one and a different-looking thing that
    // happens to move to the same music.
    //
    // The web reduces `getByteFrequencyData`, which Web Audio defines as a
    // decibel scale clamped between -100 and -30 dB. Hearing is logarithmic, so
    // a linear spectrum draws a nearly flat floor with two or three spikes:
    // everything quiet enough to be interesting disappears, and the display only
    // reacts to peaks. On a dB scale the body of the sound is visible, which is
    // what a viewer reads as the music.
    //
    // Takes magnitudes already divided by the window size, which is where Web
    // Audio's own normalisation sits and where the time smoothing has to happen
    // before it.
    private fun toDecibelScale(magnitudes: DoubleArray): DoubleArray {
        val span: Double = MAX_DECIBELS - MIN_DECIBELS

        return DoubleArray(magnitudes.size) { bin ->
            val scaled: Double = magnitudes[bin]
            // Silence is zero rather than negative infinity. log10(0) would
            // reach the consumer as NaN and draw nothing at all.
            if (scaled <= 0.0) {
                0.0
            } else {
                val decibels: Double = DECIBEL_FACTOR * log10(scaled)
                ((decibels - MIN_DECIBELS) / span).coerceIn(0.0, 1.0)
            }
        }
    }

    // The average magnitude across a frequency range.
    //
    // An average rather than a sum, because a sum makes a band's reading depend
    // on how wide it is — treble spans twelve kilohertz and bass two hundred
    // hertz, so summing would light treble on any music at all.
    public fun bandEnergy(magnitudes: DoubleArray, binHz: Double, loHz: Double, hiHz: Double): Double {
        if (binHz <= 0.0) return 0.0

        val first: Int = (loHz / binHz).toInt().coerceIn(0, magnitudes.size)
        val last: Int = (hiHz / binHz).toInt().coerceIn(first, magnitudes.size)
        if (last <= first) return 0.0

        var total = 0.0
        for (bin in first until last) total += magnitudes[bin]
        return total / (last - first)
    }

    // Where the loudest bin sits, in hertz.
    private fun peakFrequency(magnitudes: DoubleArray, binHz: Double): Double {
        var loudestBin = 0
        var loudest = 0.0
        for (bin in magnitudes.indices) {
            if (magnitudes[bin] > loudest) {
                loudest = magnitudes[bin]
                loudestBin = bin
            }
        }
        return loudestBin * binHz
    }

    // Rise instantly, fall slowly.
    //
    // A peak that tracked the bar exactly would draw the same line twice and
    // show nothing; one that never fell would climb to full scale in the first
    // loud moment and stay there for the rest of the album.
    private fun held(previous: BandEnergies?, now: BandEnergies): BandEnergies {
        if (previous == null) return now

        return BandEnergies(
            bass = maxOf(now.bass, previous.bass - PEAK_DECAY),
            mid = maxOf(now.mid, previous.mid - PEAK_DECAY),
            treble = maxOf(now.treble, previous.treble - PEAK_DECAY),
        )
    }

    // Into -1..1, which is what a scope expects. Silence stays silence rather
    // than becoming a division by zero.
    private fun normalise(samples: DoubleArray): DoubleArray {
        var loudest = 0.0
        for (sample in samples) {
            val size: Double = if (sample < 0.0) -sample else sample
            if (size > loudest) loudest = size
        }
        if (loudest <= 0.0) return DoubleArray(samples.size)

        return DoubleArray(samples.size) { index -> samples[index] / loudest }
    }

    // The edges the web player uses, and the reason they are not evenly spaced:
    // they follow how hearing divides sound rather than how a number line does.
    private const val BASS_LOW_HZ = 20.0
    private const val BASS_HIGH_HZ = 250.0
    private const val MID_LOW_HZ = 250.0
    private const val MID_HIGH_HZ = 4_000.0
    private const val TREBLE_LOW_HZ = 4_000.0
    private const val TREBLE_HIGH_HZ = 16_000.0

    private const val BAND_COUNT = 3.0

    // Per frame, matching the web's PEAK_DECAY. At sixty frames a second a peak
    // takes about five seconds to fall from full scale, which is slow enough to
    // read and fast enough to follow a track.
    private const val PEAK_DECAY = 0.003

    // Web Audio's own defaults, so the two players agree about what quiet looks
    // like. Anything below -100 dB is silence and anything above -30 is full
    // scale; that window is what makes the middle of the range visible.
    private const val MIN_DECIBELS = -100.0
    private const val MAX_DECIBELS = -30.0

    // Twenty, because these are amplitudes rather than powers.
    private const val DECIBEL_FACTOR = 20.0
}
