// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.serialization.Serializable
import tv.nomercy.player.core.dsp.DEFAULT_BANDWIDTH
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.dsp.EqPreset
import tv.nomercy.player.core.ports.Storage

// Everything the equaliser remembers, in one place away from the plugin.
//
// The plugin is already at the width of its own subject — ten sliders, a
// headroom control, a preset list and a graph to talk to. The catalogue of
// custom presets and the reading and writing of them is a second subject, and
// keeping it here is what stops the plugin becoming the file nobody wants to
// open.
internal class EqualizerStore(private val opts: EqualizerOptions) {

    private val custom: LinkedHashMap<String, EqPreset> = LinkedHashMap()

    fun customPresets(): List<EqPreset> = custom.values.toList()

    fun add(preset: EqPreset) {
        custom[preset.name] = preset
    }

    fun remove(name: String): Boolean = custom.remove(name) != null

    fun replaceAll(presets: List<EqPreset>) {
        custom.clear()
        for (preset in presets) custom[preset.name] = preset
    }

    // The write that follows a change, when the consumer asked for one.
    //
    // Two conditions rather than one: a host can keep a key and still turn the
    // automatic write off, which is how a settings screen commits on close
    // instead of on every step of a drag.
    suspend fun autoSave(storage: Storage, state: EqualizerState) {
        if (!opts.autoSave) return

        save(storage, state)
    }

    suspend fun save(storage: Storage, state: EqualizerState) {
        val key: String = opts.persistKey ?: return

        storage.setJSON(key, state.stored(customPresets()), StoredEqualizerState.serializer())
    }

    // Null means "carry on with what you have" for all three reasons it can
    // happen: no key, loading switched off, or nothing stored yet. A caller
    // resetting to flat on a null would throw away a curve every time a host
    // launched with autoLoad off.
    suspend fun loadPersisted(storage: Storage): EqualizerState? {
        if (!opts.autoLoad) return null
        val key: String = opts.persistKey ?: return null

        return storage.getJSON(key, StoredEqualizerState.serializer())?.restored()
    }
}

// The equaliser's whole position, as one value.
//
// Passed rather than reached for: the store never reads the plugin's fields, so
// there is exactly one moment a snapshot is taken and no way for half of one
// change and half of the next to be written together.
internal data class EqualizerState(
    val bands: List<EqBand>,
    val preset: String,
    val preGain: Double,
    val customPresets: List<EqPreset>,
)

// A shape of its own rather than serialising EqBand directly.
//
// EqBand and EqPreset are carried by the web trio too, so marking them
// serialisable would put a generated companion on a cross-project contract type
// for the sake of a private storage blob. Stored data also outlives the class it
// came from, and a rename in the model would silently orphan every listener's
// settings if the two were the same declaration.
@Serializable
internal data class StoredEqualizerState(
    val bands: List<StoredBand> = emptyList(),
    val selectedPreset: String? = null,
    val customPresets: List<StoredPreset> = emptyList(),
    val preGain: Double = 1.0,
)

@Serializable
internal data class StoredBand(
    val frequency: Int,
    val gain: Double,
    val bandwidth: Double = DEFAULT_BANDWIDTH,
)

@Serializable
internal data class StoredPreset(
    val name: String,
    val bands: List<StoredBand>,
)

private fun EqualizerState.stored(presets: List<EqPreset>): StoredEqualizerState = StoredEqualizerState(
    bands = bands.map { it.stored() },
    selectedPreset = preset,
    customPresets = presets.map { preset -> StoredPreset(preset.name, preset.bands.map { it.stored() }) },
    preGain = preGain,
)

private fun EqBand.stored(): StoredBand = StoredBand(
    frequency = frequency,
    gain = gainDb,
    bandwidth = bandwidth,
)

private fun StoredEqualizerState.restored(): EqualizerState = EqualizerState(
    bands = bands.map { it.restored() },
    preset = selectedPreset.orEmpty(),
    preGain = preGain,
    customPresets = customPresets.map { preset -> EqPreset(preset.name, preset.bands.map { it.restored() }) },
)

private fun StoredBand.restored(): EqBand = EqBand(
    frequency = frequency,
    gainDb = gain,
    bandwidth = bandwidth,
)
