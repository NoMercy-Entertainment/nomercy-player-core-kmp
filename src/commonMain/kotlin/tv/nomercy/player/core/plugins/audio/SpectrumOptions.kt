// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

/**
 * How the spectrum plugin analyses the sound.
 *
 * Every field is nullable and means "inherit" rather than carrying a literal
 * default. The analyser is SHARED with the audio graph, so a value written here
 * changes what every other listener on it sees — a spectrum asking for a
 * different FFT size would silently re-resolve the visualiser drawing beside it.
 * Null leaves the graph's own value alone, which is the only answer that stays
 * true when a second consumer appears.
 */
public data class SpectrumOptions(
    /**
     * FFT size for the analyser. Finer frequency resolution costs time
     * resolution. Inherits the audio graph's shared analyser when null, which is
     * usually 2048.
     */
    val fftSize: FftSize? = null,

    /**
     * Smoothing, 0..1. Higher values make the output lag behind transients.
     * Inherits the shared analyser when null, usually 0.8.
     */
    val smoothingTimeConstant: Double? = null,

    /**
     * Analysis tick rate in Hz. Null ticks at the canvas plugin's frame rate.
     *
     * Reserved, and said so rather than left to be discovered: the web declares
     * it and does not read it yet, and a port that quietly honoured it here
     * would behave differently from the player it is a port of.
     */
    val frameRate: Int? = null,

    /**
     * Analyse the channels separately, adding left and right frequency and
     * waveform data to each frame.
     *
     * Costs two extra analysers and four extra buffers per frame, which is why
     * it is off rather than always on.
     */
    val stereo: Boolean = false,
)

/**
 * The four sizes an analyser accepts.
 *
 * An enum because the web's type is a union of exactly these four literals: an
 * FFT size is a power of two the analyser is built around, and a number that is
 * not one of them is not a smaller spectrum, it is an error at the audio node.
 */
public enum class FftSize(public val bins: Int) {
    SIZE_512(512),
    SIZE_1024(1024),
    SIZE_2048(2048),
    SIZE_4096(4096),
}

/**
 * What the spectrum plugin emits.
 *
 * [energy] rides along with the frame because every listener recomputed the
 * same three band averages out of the same buffer otherwise — sixty times a
 * second, once per listener.
 */
public sealed interface SpectrumEvents {

    /** A fresh analysis frame, once per tick. */
    public data class Frame(
        val frame: VisualizationFrame,
        val energy: BandEnergy,
    ) : SpectrumEvents

    /** The full merged options, after any partial change. */
    public data class OptionsChanged(val options: SpectrumOptions) : SpectrumEvents
}

/** Bass, mid and treble, each 0..1. */
public data class BandEnergy(
    val bass: Double,
    val mid: Double,
    val treble: Double,
)
