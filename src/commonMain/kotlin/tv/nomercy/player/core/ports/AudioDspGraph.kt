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

    public fun removeFrameTap()

    // Bypass, rather than a flat curve. Flat still runs ten biquads over every
    // sample for nothing, and on a low-power device that is battery spent to
    // change nothing.
    public fun eqEnabled(enabled: Boolean)
}
