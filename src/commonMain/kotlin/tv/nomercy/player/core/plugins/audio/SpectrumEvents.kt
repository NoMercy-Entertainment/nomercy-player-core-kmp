// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

/**
 * What the spectrum plugin emits.
 *
 * [Frame.energy] rides along with the frame because every listener recomputed
 * the same three band averages out of the same buffer otherwise — sixty times a
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
