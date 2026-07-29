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
import tv.nomercy.player.core.plugin.pluginEventKey
import tv.nomercy.player.core.ports.AudioDspGraph
import kotlin.math.pow

public data class MixerOptions(
    /** Starting gain in dB. Zero is unity. */
    val gainDb: Double = 0.0,
    /** Starting pan. -1 is full left, 0 centre, 1 full right. */
    val pan: Double = 0.0,
    /** Symmetric dB ceiling. Values outside it are clamped rather than refused. */
    val maxGainDb: Double = DEFAULT_MAX_GAIN_DB,
)

/**
 * Master gain and stereo pan.
 *
 * The same `mixer` id the web plugin has, so a consumer moving code across
 * writes the same line:
 *
 *     player.addPlugin(MixerPlugin(graph))
 *
 * Values are CLAMPED rather than rejected, which is the web's behaviour and the
 * right one for a mixer: a slider dragged past its end should stop at the end,
 * not throw at whoever is holding it.
 *
 * Pan is the honest part. The native DSP port carries `preGain` and no panner,
 * so a graph that cannot pan says so through [MixerPanUnsupported] rather than
 * accepting the value and silently centring it — the same choice `DrmPlugin`
 * makes for a scheme a device cannot play. A mixer that reported a pan it never
 * applied would be a control a listener moves while nothing changes.
 */
public open class MixerPlugin(
    private val graph: AudioDspGraph,
    private val opts: MixerOptions = MixerOptions(),
    /**
     * Whether [graph] can pan.
     *
     * A parameter rather than a capability on the port, because adding a method
     * to `AudioDspGraph` would break every implementation that already exists
     * for a feature only one of them has. When a platform grows a panner, it
     * passes true and implements [applyPan].
     */
    private val panSupported: Boolean = false,
) : Plugin<MixerOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "mixer"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: MixerOptions get() = opts

    private var currentGainDb: Double = 0.0
    private var currentPan: Double = 0.0
    private var muted: Boolean = false

    /** Current gain in dB, clamped to the ceiling. */
    public fun gain(): Double = currentGainDb

    /** Current pan, -1 to 1. */
    public fun pan(): Double = currentPan

    public fun isMuted(): Boolean = muted

    override fun use() {
        gain(opts.gainDb)
        pan(opts.pan)
    }

    /**
     * Set the gain in dB. Out-of-range values are clamped to the ceiling.
     *
     * Applied as a linear multiplier because that is what the graph takes; dB
     * is what a person reads and linear is what a signal wants, and converting
     * in one place stops the two drifting.
     */
    public fun gain(db: Double) {
        val clamped: Double = db.coerceIn(-opts.maxGainDb, opts.maxGainDb)
        currentGainDb = clamped
        applyGain()
        emit(MixerEvents.GainChanged, clamped)
    }

    /** Set the pan, -1 to 1. Out-of-range values are clamped. */
    public fun pan(value: Double) {
        val clamped: Double = value.coerceIn(-1.0, 1.0)
        currentPan = clamped

        if (!panSupported) {
            // Reported, not swallowed. A listener moving a pan control that
            // does nothing should be told the platform cannot do it, and a
            // chrome that knows can hide the control instead of offering it.
            emit(MixerEvents.PanUnsupported, clamped)
            return
        }

        applyPan(clamped)
        emit(MixerEvents.PanChanged, clamped)
    }

    /**
     * Mute without losing the gain setting.
     *
     * Unmuting restores the dB the listener chose rather than unity, which is
     * the difference between a mute button and a reset button.
     */
    public fun mute(value: Boolean) {
        muted = value
        applyGain()
        emit(MixerEvents.MuteChanged, value)
    }

    /** Where a platform with a panner does the work. */
    protected open fun applyPan(value: Double) {}

    private fun applyGain() {
        graph.preGain(if (muted) 0.0 else dbToLinear(currentGainDb))
    }

    private fun dbToLinear(db: Double): Double = TEN.pow(db / DB_PER_DECADE)
}

// What the plugin publishes, and what a consumer subscribes to on the player.
//
// Both are given rather than left to be spelled by hand: a plugin's emit is
// namespaced on the way out, so a listener built from the wrong one is not an
// error — it is a listener that never fires. This file was written with only
// the first half and the test caught it.
public object MixerEvents {

    public val GainChanged: EventKey<Double> = EventKey("gain:changed")

    public val PanChanged: EventKey<Double> = EventKey("pan:changed")

    public val PanUnsupported: EventKey<Double> = EventKey("pan:unsupported")

    public val MuteChanged: EventKey<Boolean> = EventKey("mute:changed")

    public val GainChangedOnPlayer: EventKey<Double> =
        pluginEventKey(MixerPlugin.Manifest, "gain:changed")

    public val PanChangedOnPlayer: EventKey<Double> =
        pluginEventKey(MixerPlugin.Manifest, "pan:changed")

    public val PanUnsupportedOnPlayer: EventKey<Double> =
        pluginEventKey(MixerPlugin.Manifest, "pan:unsupported")

    public val MuteChangedOnPlayer: EventKey<Boolean> =
        pluginEventKey(MixerPlugin.Manifest, "mute:changed")
}

/** The web's 24 dB ceiling. */
public const val DEFAULT_MAX_GAIN_DB: Double = 24.0

private const val TEN: Double = 10.0

// Amplitude, not power: 20 rather than 10. Using 10 here halves every boost a
// listener asks for and is the classic way to get this wrong.
private const val DB_PER_DECADE: Double = 20.0
