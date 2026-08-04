// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.PluginOptionField
import tv.nomercy.player.core.plugin.pluginEventKey
import tv.nomercy.player.core.ports.AudioDspGraph

public data class AudioGraphOptions(
    /** Whether the equaliser stage is on when the graph comes up. */
    val eqEnabled: Boolean = false,
)

/**
 * Owns the audio graph the other audio plugins share.
 *
 * The same `audio-graph` id the web plugin has, so a consumer moving code
 * across writes the same line:
 *
 *     player.addPlugin(AudioGraphPlugin(graph))
 *     player.addPlugin(MixerPlugin(graph))
 *
 * What it is NOT is a port of the web's node chain. The web plugin exists
 * because a browser has no audio graph until somebody builds one, so it creates
 * an AudioContext and lets callers splice arbitrary AudioNodes into it. Native
 * platforms hand the graph over already built — Android through its own audio
 * stack, Apple through AVAudioEngine — and `AudioDspGraph` is that seam.
 * Inventing an insertable node chain on top would be modelling a browser's
 * problem on a platform that does not have it.
 *
 * So this owns the LIFECYCLE and the reporting, which is the part that ports:
 * whether a graph is there at all, saying so when it is not, and turning the
 * equaliser stage on and off in one place rather than in whichever plugin
 * happened to load first.
 */
public open class AudioGraphPlugin(
    private val graph: AudioDspGraph?,
    opts: AudioGraphOptions = AudioGraphOptions(),
) : Plugin<AudioGraphOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "audio-graph"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    // Held rather than fixed, so the toggle below turns the stage on for real.
    private var opts: AudioGraphOptions = opts

    override val options: AudioGraphOptions get() = opts

    override fun optionFields(): List<PluginOptionField> = listOf(
        PluginOptionField.Toggle(
            key = "eqEnabled",
            label = "Equaliser stage on at start",
            value = opts.eqEnabled,
            apply = { on -> opts = opts.copy(eqEnabled = on) },
        ),
    )

    /**
     * The graph, or null on a platform without one.
     *
     * Nullable on purpose. A device with no DSP path is an ordinary device, not
     * a broken one — a plugin handed a non-null graph that quietly did nothing
     * would make every audio feature look implemented and sound absent.
     */
    public fun graph(): AudioDspGraph? = graph

    public fun isReady(): Boolean = graph != null

    override fun use() {
        val live: AudioDspGraph = graph ?: run {
            // Said, not swallowed. A chrome that hears this can leave the
            // equaliser and the visualiser out of its menus instead of offering
            // controls that move and change nothing.
            emit(AudioGraphEvents.Unsupported, "this platform provides no audio DSP graph")
            return
        }

        live.eqEnabled(opts.eqEnabled)
        emit(AudioGraphEvents.Ready, Unit)
    }

    /**
     * Turn the equaliser stage on or off.
     *
     * Here rather than on the equaliser plugin because the stage belongs to the
     * graph: two plugins each toggling it would fight, and the last one to load
     * would win an argument nobody knew was happening.
     */
    public fun eqEnabled(enabled: Boolean) {
        val live: AudioDspGraph = graph ?: return
        live.eqEnabled(enabled)
        emit(AudioGraphEvents.ChainRebuilt, Unit)
    }
}

// What the plugin publishes, and what a consumer subscribes to on the player.
//
// Both halves, because a plugin's emit is namespaced on the way out and a
// listener built from the wrong one never fires.
public object AudioGraphEvents {

    public val Ready: EventKey<Unit> = EventKey("context:ready")

    public val Unsupported: EventKey<String> = EventKey("unsupported")

    public val ChainRebuilt: EventKey<Unit> = EventKey("chain:rebuilt")

    public val ReadyOnPlayer: EventKey<Unit> =
        pluginEventKey(AudioGraphPlugin.Manifest, "context:ready")

    public val UnsupportedOnPlayer: EventKey<String> =
        pluginEventKey(AudioGraphPlugin.Manifest, "unsupported")

    public val ChainRebuiltOnPlayer: EventKey<Unit> =
        pluginEventKey(AudioGraphPlugin.Manifest, "chain:rebuilt")
}
