// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The desktop's [AudioDspGraph] — EQ over mpv's own `af` chain, spectrum over
// an OS-level loopback tap, because the two gaps mpv's client API leaves
// needed two different substitutes rather than one.
//
// mpv exposes no raw-PCM tap: nothing plays the role libVLC's `amem`
// callback played for the plan this port was originally written against,
// and without one the shared [PcmEqualiser] this project's biquad math
// otherwise runs through cannot see a sample from mpv directly. EQ moves to
// mpv's own `af` filter chain instead (see [MpvAudioFilterChain] for why
// that is a coordinate change and not a second implementation of the
// curve).
//
// Spectrum has a substitute mpv itself cannot offer but the operating
// system can: [AudioLoopbackCapture] taps the OS's own mixed audio output —
// already carrying the `af` chain's EQ, since that runs before the mix — and
// hands the samples to a second [PcmEqualiser] running with its own shaping
// bypassed ([PcmEqualiser.eqEnabled] false), so it contributes nothing but
// the FFT accumulation every other platform's graph already shares. Built
// and wired, unverified against a real device — see [AudioLoopbackCapture]'s
// own doc for what that means and why it was accepted anyway.
public class MpvDspGraph(
    private val backend: MpvVideoBackend,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channels: Int = DEFAULT_CHANNELS,
    private val capture: AudioLoopbackCapture = defaultAudioLoopbackCapture(),
) : AudioDspGraph {

    private var bands: List<EqBand> = EqBands.DEFAULT
    private var preGain: Double = 1.0
    private var enabled: Boolean = true

    // Spectrum-only: `eqEnabled(false)` below means every sample this
    // accumulates passes through unfiltered, so it never applies the biquad
    // curve a second time on top of what mpv's `af` chain already baked into
    // the captured signal.
    private val spectrum: PcmEqualiser = PcmEqualiser(sampleRate, channels).also { it.eqEnabled(false) }
    private var capturing: Boolean = false

    override fun setEqBands(bands: List<EqBand>) {
        this.bands = bands
        apply()
    }

    override fun bandGain(frequencyHz: Int, gainDb: Double) {
        val index: Int = bands.indexOfFirst { it.frequency == frequencyHz }
        if (index < 0) return
        bands = bands.toMutableList().also { it[index] = it[index].copy(gainDb = gainDb) }
        apply()
    }

    override fun preGain(linear: Double) {
        preGain = linear
        apply()
    }

    override fun eqEnabled(enabled: Boolean) {
        this.enabled = enabled
        apply()
    }

    override fun fftSize(): Int = spectrum.fftSize()

    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription {
        if (!capturing) {
            capturing = capture.start(sampleRate, channels) { samples, frames -> spectrum.process(samples, frames) }
        }
        val inner = spectrum.installFrameTap(onFrame)
        return Subscription {
            inner.dispose()
            removeFrameTap()
        }
    }

    override fun removeFrameTap() {
        spectrum.removeFrameTap()
        if (capturing) {
            capture.stop()
            capturing = false
        }
    }

    private fun apply() {
        backend.setAudioFilter(MpvAudioFilterChain.build(bands, preGain, enabled))
    }

    private companion object {
        // CD-quality stereo — mpv's own default output shape when nothing
        // requests otherwise, and the shape [AudioLoopbackCapture]'s three
        // implementations were written against.
        const val DEFAULT_SAMPLE_RATE: Int = 44_100
        const val DEFAULT_CHANNELS: Int = 2
    }
}
