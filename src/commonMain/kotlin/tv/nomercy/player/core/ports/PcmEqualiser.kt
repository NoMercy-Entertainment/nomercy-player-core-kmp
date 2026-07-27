// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.AudioSpectrum
import tv.nomercy.player.core.dsp.BiquadCoefficients
import tv.nomercy.player.core.dsp.BiquadPeaking
import tv.nomercy.player.core.dsp.BiquadState
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.BandEnergies
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The equaliser and spectrum for any engine that hands over raw PCM.
//
// commonMain rather than per platform, because the arithmetic is the same
// everywhere and the only thing that differs is who calls it: libVLC's amem
// callback on the desktop, a processing tap on Apple. Android is the exception
// and has its own — Media3 wants an AudioProcessor with its own buffer
// lifecycle, and forcing that shape onto this one would make both worse.
//
// Deliberately the same arithmetic as Android's, not libVLC's own equaliser.
// libVLC has one built in, and it would have been fewer lines — but its bands
// are fixed at frequencies that are not the ten this project uses, so a preset
// chosen on the web would land on different frequencies here and sound like a
// different preset. A viewer moving between their desktop and their phone would
// hear the same title differently with the same settings.
//
// So it is core's biquad, which is measured against known tones in commonTest
// and again through Media3's sink on Android. The only unproven part on this
// platform is the plumbing that hands it samples, which is what the gate here
// exercises.
//
// Interleaved float is what libVLC's amem output gives, and what this takes.
// Nothing here knows about libVLC — a class that reached for it could not be
// tested without installing it, and the arithmetic is the part worth testing.
public class PcmEqualiser(
    private val sampleRate: Int,
    private val channels: Int,
) : AudioDspGraph {

    // Per channel, per band. Sharing history across channels mixes left into
    // right, which is heard as the stereo image collapsing rather than as a
    // filter that is obviously wrong.
    private var states: Array<Array<BiquadState>> =
        Array(channels) { Array(EqBands.DEFAULT.size) { BiquadState() } }

    private var bands: List<EqBand> = EqBands.DEFAULT

    private var coefficients: List<BiquadCoefficients> = tune(EqBands.DEFAULT)

    private var preGain: Double = 1.0

    private var enabled: Boolean = true

    private val window = DoubleArray(FFT_SIZE)

    private var windowFill: Int = 0

    private var frameTap: ((VisualizationFrame) -> Unit)? = null

    private var previousPeaks: BandEnergies? = null

    private var elapsedSeconds: Double = 0.0

    // In place, because this runs on libVLC's audio thread for every buffer and
    // allocating a new array per callback would put the garbage collector in
    // the audio path.
    public fun process(samples: FloatArray, frames: Int) {
        for (frame in 0 until frames) {
            accumulate(shapeFrame(samples, frame) / channels)
        }
    }

    // One frame across every channel, returning their sum so the caller can
    // average it into the spectrum window without walking the buffer twice.
    private fun shapeFrame(samples: FloatArray, frame: Int): Double {
        var sum = 0.0
        for (channel in 0 until channels) {
            val index: Int = frame * channels + channel
            val raw: Double = samples[index].toDouble()
            val shaped: Double = if (enabled) filter(raw, channel) else raw
            sum += shaped
            // Clamped, because libVLC's buffer is float and a boosted band
            // genuinely exceeds one. Passing that on hands the sound card a
            // sample it cannot represent, which is the loudest noise the
            // hardware can make.
            samples[index] = shaped.coerceIn(-1.0, 1.0).toFloat()
        }
        return sum
    }

    private fun filter(sample: Double, channel: Int): Double {
        var value: Double = sample
        for (band in coefficients.indices) {
            value = states[channel][band].step(coefficients[band], value)
        }
        return value * preGain
    }

    // Mono, because a spectrum is a picture of the music rather than of one
    // speaker, and two transforms cost twice as much to show the same thing.
    private fun accumulate(sample: Double) {
        window[windowFill] = sample
        windowFill++
        if (windowFill < FFT_SIZE) return

        windowFill = 0
        val tap: (VisualizationFrame) -> Unit = frameTap ?: return
        val deltaMs: Double = FFT_SIZE * MILLIS_PER_SECOND / sampleRate
        elapsedSeconds += deltaMs / MILLIS_PER_SECOND

        val frame: VisualizationFrame =
            AudioSpectrum.analyse(window.copyOf(), sampleRate, deltaMs, elapsedSeconds, previousPeaks)
        previousPeaks = frame.peakBandEnergies
        tap(frame)
    }

    // A seek makes the next buffer discontinuous with the last, and a filter
    // carrying history across that rings — an audible click at every seek.
    public fun reset() {
        states = Array(channels) { Array(bands.size) { BiquadState() } }
        windowFill = 0
        elapsedSeconds = 0.0
        previousPeaks = null
    }

    private fun tune(bands: List<EqBand>): List<BiquadCoefficients> = bands.map { band ->
        BiquadPeaking.coefficients(
            frequencyHz = band.frequency.toDouble(),
            gainDb = band.gainDb,
            bandwidth = band.bandwidth,
            sampleRate = sampleRate,
        )
    }

    override fun setEqBands(bands: List<EqBand>) {
        this.bands = bands
        coefficients = tune(bands)
        states = Array(channels) { Array(bands.size) { BiquadState() } }
    }

    // Recomputed in place, leaving the history alone. Rebuilding on every drag
    // frame restarts each filter mid-signal, which crackles all the way along.
    override fun bandGain(frequencyHz: Int, gainDb: Double) {
        val index: Int = bands.indexOfFirst { it.frequency == frequencyHz }
        if (index < 0) return

        val band: EqBand = bands[index].copy(gainDb = gainDb)
        bands = bands.toMutableList().also { it[index] = band }
        coefficients = coefficients.toMutableList().also {
            it[index] = BiquadPeaking.coefficients(
                frequencyHz = band.frequency.toDouble(),
                gainDb = gainDb,
                bandwidth = band.bandwidth,
                sampleRate = sampleRate,
            )
        }
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
        this.enabled = enabled
    }
}

private const val FFT_SIZE = 4_096
private const val MILLIS_PER_SECOND = 1_000.0
