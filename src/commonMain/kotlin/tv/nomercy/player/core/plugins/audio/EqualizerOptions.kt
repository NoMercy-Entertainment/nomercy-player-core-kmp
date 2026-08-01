// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqSliderValues

public data class EqualizerOptions(
    /**
     * Where the curve is kept between sessions, under the plugin's own storage
     * namespace. Null is no persistence at all, which is the web's default and
     * the right one for a library: a player that quietly starts writing to a
     * consumer's storage on registration is a surprise, and an equaliser is a
     * setting some hosts deliberately keep per session.
     *
     * [DEFAULT_PERSIST_KEY] is the conventional value. The key is already
     * prefixed with the plugin id before it reaches a backend, so it does not
     * have to be unique across plugins.
     */
    val persistKey: String? = null,
    /**
     * Whether a stored curve is read back when the plugin is registered. False
     * starts from the defaults every time while still writing what the listener
     * does, which is what a "reset on launch" host wants.
     */
    val autoLoad: Boolean = true,
    /**
     * Whether every band, preset and headroom change is written straight away.
     * False leaves the writing to the host, for a settings screen that commits
     * on close rather than on every step of a drag.
     */
    val autoSave: Boolean = true,
    /**
     * The ends and the step of the sliders a UI draws. Overridable because a
     * consumer whose design only offers ±6 dB should be able to say so once
     * rather than clamp in every control.
     */
    val sliderValues: EqSliderValues = EqBands.DEFAULT_SLIDER_VALUES,
)

// One key holds the whole equaliser: bands, headroom, the chosen preset and any
// custom ones. Split across four it would be four writes per slider step and
// four ways to restore a half-applied curve — a listener would see their bands
// come back without the headroom that made them safe.
public const val DEFAULT_PERSIST_KEY: String = "state"
