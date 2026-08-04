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
import tv.nomercy.player.core.plugin.PluginOptionField
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
    opts: EqualizerOptions,
) : Plugin<EqualizerOptions>() {

    // Spelled out rather than defaulted: a defaulted Kotlin parameter reaches
    // Swift as a required argument, so a default here would break every
    // `EqualizerPlugin(graph)` already written against the framework.
    public constructor(graph: AudioDspGraph?) : this(graph, EqualizerOptions())

    public companion object Manifest : PluginManifest {
        override val id: String = "equalizer"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    // Held rather than fixed: whether a curve is written on every drag is the
    // setting a host tunes while watching what it costs.
    private var opts: EqualizerOptions = opts

    override val options: EqualizerOptions get() = opts

    override fun optionFields(): List<PluginOptionField> = listOf(
        PluginOptionField.Toggle(
            key = "autoLoad",
            label = "Read the stored curve on registration",
            value = opts.autoLoad,
            apply = { on -> opts = opts.copy(autoLoad = on) },
        ),
        PluginOptionField.Toggle(
            key = "autoSave",
            label = "Write every band change straight away",
            value = opts.autoSave,
            apply = { on -> opts = opts.copy(autoSave = on) },
        ),
    )

    // The custom preset catalogue and the reading and writing of it, which is a
    // second subject from the sliders and lives in its own file.
    private val store: EqualizerStore = EqualizerStore(opts)

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

    // Whatever the listener left behind, put back before anything reads it.
    //
    // Asynchronous because storage is: one contract covers a synchronous
    // preference file and a remote store, and the plugin cannot know which it
    // was given. The web restores inside its own use() only because a browser's
    // localStorage answers immediately, and its own docblock admits an async
    // backend restores late there too.
    override fun use() {
        if (opts.persistKey == null) return

        this.launch {
            val restored: EqualizerState? = store.loadPersisted(storage)
            if (restored != null) {
                store.replaceAll(restored.customPresets)

                // An empty band list is a blob written before a curve was ever
                // set. Applying it would flatten a chain the listener has since
                // built rather than leave it where it is.
                if (restored.bands.isNotEmpty()) {
                    current = restored.bands
                    chosenPreset = restored.preset
                    pre = restored.preGain
                    graph?.setEqBands(current)
                    graph?.preGain(pre)
                }
            }
        }
    }

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
        persist()
    }

    public open fun preGain(): Double = pre

    public open fun preGain(sliderValue: Double) {
        pre = EqBands.preGainLinear(sliderValue)
        graph?.preGain(pre)
        persist()
    }

    // Built-ins first, then whatever the consumer added, in the order they added
    // it. A menu that reordered itself as presets were saved would move the
    // entry under a viewer's thumb between one press and the next.
    public open fun presets(): List<EqPreset> = EqPresets.BUILTIN + store.customPresets()

    // A curve the listener built, kept under a name and stored with the rest.
    //
    // Replaces silently on a name collision, matching the web: saving over
    // "Late night" is what a viewer means by saving it again, and refusing
    // would leave them with no way to correct one.
    public open fun addCustomPreset(preset: EqPreset) {
        store.add(preset)
        persist()
    }

    // Built-ins are not removable. A catalogue a viewer can empty is one they
    // can lock themselves out of, and the built-ins are the floor everything
    // else is compared against.
    public open fun removePreset(name: String) {
        if (store.remove(name)) persist()
    }

    public open fun preset(): String = chosenPreset

    // The whole curve at once, because choosing a preset is one decision. Sent
    // band by band, a listener hears the chain reshape as a swoop.
    public open fun preset(name: String) {
        // Built-in by name first, then the custom catalogue — the web's
        // resolution order, and the one that stops a saved preset shadowing a
        // built-in a viewer expects to be able to get back to.
        val preset: EqPreset = EqPresets.byName(name)
            ?: store.customPresets().firstOrNull { it.name == name }
            ?: return

        current = preset.bands
        chosenPreset = preset.name
        graph?.setEqBands(preset.bands)
        persist()
    }

    public open fun reset() {
        current = EqBands.DEFAULT
        chosenPreset = EqPresets.CUSTOM.name
        pre = 1.0
        graph?.setEqBands(EqBands.DEFAULT)
        graph?.preGain(pre)
        persist()
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

    // Nothing at all when no key was configured, which is also what keeps this
    // safe to call from a plugin nobody registered: reaching the scoped storage
    // needs a host, and a consumer who asked for no persistence should not need
    // one to move a slider.
    private fun persist() {
        if (opts.persistKey == null) return

        this.launch {
            store.autoSave(
                storage,
                EqualizerState(
                    bands = current,
                    preset = chosenPreset,
                    preGain = pre,
                    customPresets = store.customPresets(),
                ),
            )
        }
    }
}
