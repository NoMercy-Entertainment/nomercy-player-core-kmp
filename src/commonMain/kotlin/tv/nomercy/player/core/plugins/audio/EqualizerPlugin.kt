// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPreset
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.ports.AudioDspGraph

// The ten sliders, and the presets that move them together.
//
// The graph arrives through the constructor rather than through the player, and
// that is not a convenience. The method-surface gate rejected the first version
// of this, which reached the graph through PluginHost: the web player has no
// audioGraph() on its player either — it gets one from a plugin — and adding it
// here would have put a method on the shared contract that only one of the two
// implementations has.
//
// The host constructs this with the graph its engine gave it, which is ordinary
// dependency injection and keeps every engine method out of a plugin's reach.
// A plugin that could call load() behind the player's back is a plugin that can
// desynchronise the state every controller above depends on.
public open class EqualizerPlugin(
    private val graph: AudioDspGraph?,
) : Plugin<Unit>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "equalizer"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    // The live curve, held here rather than read back from the graph.
    //
    // A graph is a one-way street on most platforms — Media3's processor and
    // AVAudioEngine's nodes take values and do not hand them back — so a UI
    // asking "where is this slider" has to be answered from somewhere. This is
    // that somewhere, and it is written only when the graph is told.
    private var current: List<EqBand> = EqBands.DEFAULT

    private var chosenPreset: String = EqPresets.CUSTOM.name

    private var pre: Double = 1.0

    private var on: Boolean = true

    public open fun bands(): List<EqBand> = current

    // One band, which is what a slider drag is. The graph is told about this
    // band only — rebuilding the whole chain on every drag frame drops the
    // filter state and clicks sixty times a second.
    public open fun band(frequencyHz: Int, gainDb: Double) {
        val index: Int = current.indexOfFirst { it.frequency == frequencyHz }
        if (index < 0) return

        current = current.toMutableList().also { it[index] = it[index].copy(gainDb = gainDb) }
        // Moving a slider makes the curve the viewer's own, whatever it was
        // called a moment ago. A UI still showing "Rock" after the viewer has
        // edited it is claiming something untrue about what they are hearing.
        chosenPreset = EqPresets.CUSTOM.name
        graph?.bandGain(frequencyHz, gainDb)
    }

    public open fun preGain(): Double = pre

    public open fun preGain(sliderValue: Double) {
        pre = EqBands.preGainLinear(sliderValue)
        graph?.preGain(pre)
    }

    public open fun presets(): List<EqPreset> = EqPresets.BUILTIN

    public open fun preset(): String = chosenPreset

    // The whole curve at once, because choosing a preset is one decision. Sent
    // band by band, a listener hears the chain reshape as a swoop.
    public open fun preset(name: String) {
        val preset: EqPreset = EqPresets.byName(name) ?: return

        current = preset.bands
        chosenPreset = preset.name
        graph?.setEqBands(preset.bands)
    }

    public open fun reset() {
        current = EqBands.DEFAULT
        chosenPreset = EqPresets.CUSTOM.name
        pre = 1.0
        graph?.setEqBands(EqBands.DEFAULT)
        graph?.preGain(pre)
    }

    public open fun enabled(equalizer: Boolean) {
        on = equalizer
        graph?.eqEnabled(equalizer)
    }

    // Distinct from Plugin.enabled(), which is whether the plugin is running at
    // all. A viewer can switch the equaliser off while the plugin stays
    // registered, and those are different states — one keeps the curve, the
    // other forgets there was one.
    public open fun equalizerEnabled(): Boolean = on

    // Whether there is anywhere for these numbers to go.
    //
    // A UI should draw the controls disabled rather than let a viewer move
    // sliders that change nothing. Silently accepting the calls is how a
    // control comes to look broken instead of absent.
    public open fun available(): Boolean = graph != null
}
