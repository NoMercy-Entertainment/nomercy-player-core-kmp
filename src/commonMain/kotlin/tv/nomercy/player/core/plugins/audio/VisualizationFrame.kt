// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

// How much of the sound is low, middle and high.
//
// Three numbers rather than the whole spectrum because that is what a
// visualiser actually binds to. Drawing a bass pulse from four thousand bins
// means every consumer writes the same summation, and they would each pick
// slightly different edges.
public data class BandEnergies(
    val bass: Double,
    val mid: Double,
    val treble: Double,
)

// One frame of analysed audio, as a visualiser receives it.
//
// The field names track the web player's `VisualizationFrame` deliberately.
// A plugin author moving between the two should not have to learn a second
// vocabulary for the same numbers, and the parity machine compares these names
// directly.
//
// Everything is already normalised. A consumer binding a bar chart to
// `frequency` or a scope to `waveform` should not have to discover the scale by
// experiment, and a reducer that returned raw magnitudes would have every
// consumer inventing its own ceiling.
public data class VisualizationFrame(
    // Bin magnitudes, 0..1. Half the transform, because a real signal's
    // spectrum is symmetric and the upper half is the lower one mirrored.
    val frequency: DoubleArray,
    // The windowed input, -1..1.
    val waveform: DoubleArray,
    val time: Double,
    val deltaMs: Double,
    val energy: Double,
    val bandEnergies: BandEnergies,
    val sampleRate: Int,
    val binHz: Double,
    val peakHz: Double,
    // Where each band has been recently, so a visualiser can draw the peak line
    // a viewer reads as loudness history.
    val peakBandEnergies: BandEnergies,
    // Null rather than false, because "no beat here" and "nobody is looking for
    // beats" are different things and a consumer draws them differently.
    val beat: Boolean? = null,
    val bpm: Double? = null,
) {
    // DoubleArray compares by identity, so the generated equals would call two
    // frames holding identical numbers different. Nothing in the library
    // depends on that, but a consumer caching frames by value would be silently
    // wrong, and a data class that lies about equality is worse than one that
    // has none.
    //
    // As complex as the type is wide, which is the whole of the complexity
    // warning. Splitting it into helpers would hide which fields participate,
    // and a comparison you have to follow across three functions is how a field
    // gets left out of it.
    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizationFrame) return false

        return frequency.contentEquals(other.frequency) &&
            waveform.contentEquals(other.waveform) &&
            time == other.time &&
            deltaMs == other.deltaMs &&
            energy == other.energy &&
            bandEnergies == other.bandEnergies &&
            sampleRate == other.sampleRate &&
            binHz == other.binHz &&
            peakHz == other.peakHz &&
            peakBandEnergies == other.peakBandEnergies &&
            beat == other.beat &&
            bpm == other.bpm
    }

    override fun hashCode(): Int {
        var result: Int = frequency.contentHashCode()
        result = HASH_FACTOR * result + waveform.contentHashCode()
        result = HASH_FACTOR * result + time.hashCode()
        result = HASH_FACTOR * result + energy.hashCode()
        result = HASH_FACTOR * result + bandEnergies.hashCode()
        result = HASH_FACTOR * result + peakHz.hashCode()
        return result
    }
}

private const val HASH_FACTOR = 31
