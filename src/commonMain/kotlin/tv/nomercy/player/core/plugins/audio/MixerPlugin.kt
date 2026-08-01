// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.serialization.Serializable
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
    /**
     * Where gain, pan and mute are kept between sessions, under the plugin's own
     * storage namespace. Null is no persistence, matching the web: a library
     * that starts writing to a consumer's storage the moment it is registered is
     * a surprise, and [DEFAULT_PERSIST_KEY] is the conventional value for the
     * hosts that want it.
     */
    val persistKey: String? = null,
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

    // Set while the plugin itself is moving the controls rather than a listener.
    // Without it the configured defaults applied at registration are written
    // straight back over the stored blob before the restore has had a chance to
    // read it, which loses the setting on exactly the launch that was meant to
    // bring it back.
    private var applying: Boolean = false

    /** Current gain in dB, clamped to the ceiling. */
    public fun gain(): Double = currentGainDb

    /** Current pan, -1 to 1. */
    public fun pan(): Double = currentPan

    public fun isMuted(): Boolean = muted

    override fun use() {
        applying = true
        gain(opts.gainDb)
        pan(opts.pan)
        applying = false
        loadPersisted()
    }

    // What the listener left the mixer at, put back.
    //
    // After the configured defaults rather than instead of them, so a host with
    // an asynchronous store still starts at a sane gain instead of at whatever
    // silence a pending read leaves behind. Applied through the ordinary setters,
    // which is what makes a chrome bound to `gain:changed` redraw when a late
    // restore lands.
    private fun loadPersisted() {
        val key: String = opts.persistKey ?: return

        this.launch {
            val stored: StoredMixerState? = storage.getJSON(key, StoredMixerState.serializer())
            if (stored != null) {
                applying = true
                gain(stored.gain)
                pan(stored.pan)
                mute(stored.muted)
                applying = false
            }
        }
    }

    // Every change, because there is no moment a mixer is "done" being adjusted
    // and a host that had to call save would eventually ship one that forgot.
    private fun autoSave() {
        val key: String = opts.persistKey ?: return
        if (applying) return

        this.launch {
            storage.setJSON(
                key,
                StoredMixerState(gain = currentGainDb, pan = currentPan, muted = muted),
                StoredMixerState.serializer(),
            )
        }
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
        autoSave()
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
        autoSave()
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
        autoSave()
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

// The mixer's whole position, as one stored value.
//
// One key rather than three: split, a restore could land the gain of one session
// with the mute of another, which is a listener opening the app to silence they
// never asked for and no control that looks wrong.
@Serializable
internal data class StoredMixerState(
    val gain: Double,
    val pan: Double,
    val muted: Boolean,
)
