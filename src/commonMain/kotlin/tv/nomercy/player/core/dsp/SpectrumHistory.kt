// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import tv.nomercy.player.core.plugins.audio.BandEnergies

// What one frame of analysis remembers about the one before it.
//
// Two carries, both held by whoever drives the analysis rather than by the
// arithmetic: the peak line, which was already threaded through as a parameter,
// and the smoothed magnitudes, which were not — so a native visualiser flickered
// frame to frame where the web one glides.
//
// The web gets the second one for free. `AnalyserNode` blends each block into
// the last at a time constant of 0.8 before it ever reports a number, so what a
// browser visualiser draws has already been steadied. Recomputing a raw FFT
// every frame and drawing it is a different picture of the same music: the bars
// jitter on noise between transients, which reads as a broken display rather
// than as detail.
//
// Mutable, and deliberately: it is a caller's state, the same way `previousPeaks`
// was a field on `PcmEqualiser`. Handing the same instance to the analysis and
// to `SpectrumPlugin` is what makes the plugin's smoothing knob reach the audio
// path without a new method on `AudioDspGraph`.
public class SpectrumHistory(smoothingTimeConstant: Double) {

    // Written out rather than defaulted, because a defaulted Kotlin parameter
    // reaches Swift as a required argument and every existing call site would
    // have to grow one.
    public constructor() : this(DEFAULT_SMOOTHING_TIME_CONSTANT)

    // Zero is no smoothing at all and one is a display that never moves again;
    // anything outside that is not a slower or faster version of either, it is
    // a filter that runs away. Clamped rather than refused, matching how every
    // other control in the audio path treats an out-of-range value.
    public var smoothingTimeConstant: Double = smoothingTimeConstant.coerceIn(0.0, 1.0)
        set(value) {
            field = value.coerceIn(0.0, 1.0)
        }

    internal var magnitudes: DoubleArray? = null

    internal var peaks: BandEnergies? = null

    // A track change, a seek, or a stopped engine. Carrying the old spectrum
    // across one would bleed the previous track into the first frames of the
    // next, which is visible as a visualiser that keeps moving through silence.
    public fun reset() {
        magnitudes = null
        peaks = null
    }

    // Web Audio's own blend, in the same place it happens there: on the linear
    // magnitudes, before the decibel conversion. Smoothing the converted values
    // instead would average logarithms rather than take the logarithm of an
    // average, and the two disagree on exactly the transients this exists to
    // smooth.
    internal fun smooth(current: DoubleArray): DoubleArray {
        val previous: DoubleArray? = magnitudes

        // A first frame, or an FFT size that changed under us. Seeded with the
        // frame itself rather than with zeroes: the browser's analyser starts
        // from silence and spends its first frames climbing out of it, which
        // after a track change is a visualiser that fades in for no reason the
        // listener can hear.
        if (previous == null || previous.size != current.size) {
            magnitudes = current.copyOf()
            return current
        }

        val carry: Double = smoothingTimeConstant
        val blended = DoubleArray(current.size) { bin ->
            carry * previous[bin] + (1.0 - carry) * current[bin]
        }
        magnitudes = blended
        return blended
    }
}

// The web's shared analyser default, set once in AudioGraphPlugin and inherited
// by everything that reads from it.
public const val DEFAULT_SMOOTHING_TIME_CONSTANT: Double = 0.8
