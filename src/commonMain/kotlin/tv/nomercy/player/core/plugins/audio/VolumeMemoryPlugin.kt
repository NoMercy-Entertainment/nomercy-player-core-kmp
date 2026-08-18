// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.serialization.Serializable
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.MuteChange
import tv.nomercy.player.core.events.VolumeChange
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.VolumeCommands

public data class VolumeMemoryOptions(
    /**
     * Storage key volume/mute state is persisted under, under the plugin's own
     * storage namespace. Null (the reference's default `'volume'` applies) is
     * fine for the common case of one player per host; a host running more
     * than one player instance under the same storage namespace names a key
     * per instance the same way [MixerOptions.persistKey] does.
     */
    val persistKey: String? = null,
)

/**
 * Remembers volume and mute state across sessions.
 *
 * The same `volume-memory` id the web plugin has. Shared by video and music —
 * the [VolumeCommands] surface this restores through and saves from is
 * identical on both, so one plugin covers both consumers, same as the web
 * reference covers both from one file in core.
 *
 *     player.addPlugin(VolumeMemoryPlugin(volumeCommands))
 */
public open class VolumeMemoryPlugin(
    private val volume: VolumeCommands,
    private val opts: VolumeMemoryOptions = VolumeMemoryOptions(),
) : Plugin<VolumeMemoryOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "volume-memory"

        // One, matching the web plugin this mirrors.
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: VolumeMemoryOptions get() = opts

    private var currentLevel: Int = DEFAULT_LEVEL
    private var currentMuted: Boolean = false

    // Set while the restored values are being applied, so the mute/volume
    // events those calls raise do not immediately overwrite the just-read
    // stored blob with itself before the restore has finished — the same
    // guard MixerPlugin.applying protects.
    private var restoring: Boolean = false

    override fun use() {
        on(CoreEvents.Volume) { change: VolumeChange ->
            currentLevel = change.level
            if (!restoring) save()
        }
        on(CoreEvents.Mute) { change: MuteChange ->
            currentMuted = change.muted
            if (!restoring) save()
        }

        loadPersisted()
    }

    private fun key(): String = opts.persistKey ?: DEFAULT_VOLUME_PERSIST_KEY

    private fun loadPersisted() {
        this.launch {
            val stored: StoredVolumeState? = storage.getJSON(key(), StoredVolumeState.serializer())
            if (stored != null) {
                restoring = true
                volume.volume(stored.level)
                if (stored.muted) volume.mute()
                restoring = false
            }
        }
    }

    private fun save() {
        this.launch {
            storage.setJSON(
                key(),
                StoredVolumeState(level = currentLevel, muted = currentMuted),
                StoredVolumeState.serializer(),
            )
        }
    }
}

private const val DEFAULT_LEVEL: Int = 100

// The web's default persist key — see VolumeMemoryOptions.persistKey.
private const val DEFAULT_VOLUME_PERSIST_KEY: String = "volume"

@Serializable
internal data class StoredVolumeState(
    val level: Int,
    val muted: Boolean,
)
