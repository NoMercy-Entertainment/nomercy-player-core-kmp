// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBand

/**
 * What the equaliser reports.
 *
 * [PresetChanged] carries a null name after a reset, which is the difference
 * between "no preset" and "a preset called nothing" — a chrome showing the
 * selected preset has to clear its label rather than print an empty chip.
 *
 * [BandChanged] and [Changed] both exist because a slider being dragged emits
 * one band forty times a second while a preset being applied changes all of
 * them at once. A listener drawing one bar wants the first; one persisting the
 * whole curve wants the second and would otherwise write ten times per drag.
 */
public sealed interface EqualizerEvents {

    /** The filter chain is wired and any saved state is restored. */
    public data object Ready : EqualizerEvents

    public data class BandChanged(val band: EqBand) : EqualizerEvents

    /** Null after a reset. */
    public data class PresetChanged(val name: String?) : EqualizerEvents

    public data class Changed(
        val bands: List<EqBand>,
        val selectedPreset: String?,
    ) : EqualizerEvents

    /** The current settings were persisted. */
    public data object Saved : EqualizerEvents
}
