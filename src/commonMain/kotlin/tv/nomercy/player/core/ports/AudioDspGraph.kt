// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The signal path between the decoder and the speakers, as a plugin sees it.
//
// The arithmetic of an equaliser and a spectrum is already written and shared —
// the biquad, the FFT, the band layout. What is not shared is where in the
// audio path that arithmetic runs, and it cannot be: Android inserts an
// AudioProcessor into Media3's sink, Apple attaches nodes to an AVAudioEngine,
// the desktop asks libVLC for a filter chain. Three different places, one set of
// numbers.
//
// So this is the seam. A plugin decides what the sound should be; a backend
// owns where that happens. Neither has to know the other's half.
public interface AudioDspGraph {

    // Install or replace the whole chain. Called when a preset is chosen, which
    // moves every band at once — sending ten separate updates would let a
    // listener hear the chain reshape one band at a time.
    public fun setEqBands(bands: List<EqBand>)

    // Move one band without rebuilding the chain, which is what a slider drag
    // does sixty times a second. Rebuilding on each would drop the filter state
    // and click on every frame.
    public fun bandGain(frequencyHz: Int, gainDb: Double)

    // A linear multiplier, not decibels. Boosting ten bands at once overflows
    // the signal, and this is the headroom that stops it clipping; the slider
    // sits at one, which is unity.
    public fun preGain(linear: Double)

    // Frames for whatever is drawing them.
    //
    // A subscription rather than a setter, because more than one thing wants
    // frames — a visualiser and a beat detector at the same time — and a setter
    // makes the second one silently replace the first.
    public fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription

    /**
     * How many samples each analysis frame is taken over.
     *
     * The reference exposes this on the spectrum plugin and changes it at
     * runtime, because the right answer depends on what is being drawn: a
     * twelve-bar level meter wants a small transform and a fast response, a
     * scrolling spectrogram wants a large one and finer bins. With no way to
     * ask, a visualiser had to be written against whatever this graph happened
     * to choose.
     *
     * Powers of two from 256 to 4096, as the reference constrains it. A value
     * outside that is clamped rather than refused: a visualiser asking for
     * more resolution than a backend offers should draw something.
     *
     * Defaults to reporting the fixed size and ignoring writes, so a backend
     * whose analysis window is not adjustable keeps compiling and answers
     * honestly about what it does.
     */
    public fun fftSize(): Int = DEFAULT_FFT_SIZE

    public fun fftSize(samples: Int) {
        // Nothing by default. A backend that can retune overrides both halves.
    }

    public fun removeFrameTap()

    // Bypass, rather than a flat curve. Flat still runs ten biquads over every
    // sample for nothing, and on a low-power device that is battery spent to
    // change nothing.
    public fun eqEnabled(enabled: Boolean)
}

// The reference's own default, and the midpoint of the range it allows.
public const val DEFAULT_FFT_SIZE: Int = 2048
