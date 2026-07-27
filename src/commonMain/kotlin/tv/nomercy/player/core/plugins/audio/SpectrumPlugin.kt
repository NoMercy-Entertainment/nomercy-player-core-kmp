// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.AudioSpectrum
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.ports.AudioDspGraph

// Frames from the audio path, held for whoever is drawing.
//
// One tap on the graph, however many visualisers there are. Each one opening
// its own tap would mean the same FFT run several times a frame, and on a phone
// that is the difference between a visualiser and a warm battery.
//
// Listeners are registered on the plugin rather than emitted through the host
// bus. A frame arrives sixty times a second and carries two arrays; putting
// that on the shared bus makes every listener in the player pay to ignore it.
public open class SpectrumPlugin(
    private val graph: AudioDspGraph?,
) : Plugin<Unit>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "spectrum"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    private val listeners: MutableList<(VisualizationFrame) -> Unit> = mutableListOf()

    private var tap: Subscription? = null

    private var latest: VisualizationFrame? = null

    private var synthetic: Boolean = false

    // The last frame that arrived, for anything drawing on its own clock.
    //
    // A visualiser driven by the display's refresh rather than by the audio
    // wants to ask rather than be told, and the two rates are never the same.
    public open fun currentFrame(): VisualizationFrame? = latest

    public open fun onFrame(fn: (VisualizationFrame) -> Unit): Subscription {
        listeners += fn
        ensureTap()
        return Subscription { listeners.remove(fn) }
    }

    // Opened once, on the first listener, and never re-opened while it is live.
    // Opening a second would leave the first orphaned in the graph, still
    // running its FFT with nobody reading the result.
    private fun ensureTap() {
        if (tap != null) return

        tap = graph?.installFrameTap { frame -> deliver(frame) }
    }

    // A copy of the list, because a visualiser that removes itself from inside
    // render — which is what one does when its view is detached mid-frame —
    // would otherwise be mutating the list being walked.
    private fun deliver(frame: VisualizationFrame) {
        latest = frame
        for (listener in listeners.toList()) listener(frame)
    }

    // Frames from somewhere other than the audio path.
    //
    // For a visualiser being built with no sound playing, and for the case that
    // matters more: a device where the audio path cannot be tapped at all.
    // Drawing something plausible is better than a dead rectangle, as long as
    // nobody is told it is the music.
    public open fun syntheticMode(enabled: Boolean) {
        synthetic = enabled
    }

    public open fun syntheticMode(): Boolean = synthetic

    // Rejected unless synthetic mode is on, so a stray call cannot make a
    // visualiser show something that was never in the audio. A viewer watching
    // a display that does not match what they hear has no way to tell which of
    // the two is wrong.
    public open fun pushFrame(frame: VisualizationFrame) {
        if (!synthetic) return

        deliver(frame)
    }

    // The average level across a frequency range of the most recent frame.
    //
    // Zero before the first frame rather than a guess. A consumer polling this
    // to size a bar gets a bar of nothing, which is the truth.
    public open fun bandEnergy(loHz: Double, hiHz: Double): Double {
        val frame: VisualizationFrame = latest ?: return 0.0

        return AudioSpectrum.bandEnergy(frame.frequency, frame.binHz, loHz, hiHz)
    }

    public open fun available(): Boolean = graph != null

    // The tap is the plugin's, not the listeners'. A host tearing the plugin
    // down while a view still holds a subscription must still stop the graph
    // running an FFT into nothing.
    override fun dispose() {
        tap?.dispose()
        tap = null
        listeners.clear()
        latest = null
    }
}
