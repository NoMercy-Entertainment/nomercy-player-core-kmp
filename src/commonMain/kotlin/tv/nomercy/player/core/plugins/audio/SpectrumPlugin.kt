// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.AudioSpectrum
import tv.nomercy.player.core.dsp.SpectrumHistory
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.DEFAULT_FFT_SIZE
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
// The web plugin's public surface, method for method.
@Suppress("TooManyFunctions")
public open class SpectrumPlugin(
    private val graph: AudioDspGraph?,
    // The same instance the analysis was built with, which is what makes
    // [smoothingTimeConstant] a live control rather than a number the plugin
    // keeps to itself. A host that wires neither gets the web's default on both
    // sides and the knob moves nothing, which is honest: there is no analysis
    // to steady.
    private val history: SpectrumHistory,
) : Plugin<Unit>() {

    // Spelled out rather than defaulted: a defaulted Kotlin parameter reaches
    // Swift as a required argument, so a default here would break every
    // `SpectrumPlugin(graph)` already written against the framework.
    public constructor(graph: AudioDspGraph?) : this(graph, SpectrumHistory())

    public companion object Manifest : PluginManifest {
        override val id: String = "spectrum"
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    private val listeners: MutableList<(VisualizationFrame) -> Unit> = mutableListOf()

    private val beatProviders: MutableList<() -> BeatReading> = mutableListOf()

    private var tap: Subscription? = null

    private var latest: VisualizationFrame? = null

    private var synthetic: Boolean = false

    // The last frame pushed by hand, kept so turning the mode off can throw it
    // away deliberately rather than leaving it as the answer to currentFrame().
    private var syntheticFrame: VisualizationFrame? = null

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

        // Tap frames are dropped while synthetic mode is on rather than the
        // tap being closed. A viewer toggling the mode expects the real
        // spectrum back immediately, and reopening a tap costs a frame or two
        // of blank display every time.
        tap = graph?.installFrameTap { frame -> if (!synthetic) deliver(stamp(frame)) }
    }

    // Whatever the beat detectors say about this frame.
    //
    // Any provider reporting a beat makes it a beat, and the last one to name a
    // tempo names it — the web's resolution, and the right one: two detectors
    // disagreeing about whether a kick landed is not a reason to drop both.
    //
    // Null stays null when nobody is looking. A visualiser draws "no beat here"
    // and "nobody is detecting beats" differently, and collapsing them to false
    // would make every player without a detector look like one playing silence.
    private fun stamp(frame: VisualizationFrame): VisualizationFrame {
        if (beatProviders.isEmpty()) return frame

        var beat: Boolean? = null
        var bpm: Double? = null
        for (provider in beatProviders.toList()) {
            val reading: BeatReading = provider()
            if (reading.beat == true) beat = true
            if (reading.bpm != null) bpm = reading.bpm
        }

        return frame.copy(beat = beat, bpm = bpm)
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
        // Leaving the last synthetic frame behind would let it be read as the
        // current one after the real spectrum came back, so a visualiser
        // polling currentFrame would draw a shape the audio never had.
        if (!enabled) syntheticFrame = null
    }

    public open fun syntheticMode(): Boolean = synthetic

    // Rejected unless synthetic mode is on, so a stray call cannot make a
    // visualiser show something that was never in the audio. A viewer watching
    // a display that does not match what they hear has no way to tell which of
    // the two is wrong.
    public open fun pushFrame(frame: VisualizationFrame) {
        if (!synthetic) return

        syntheticFrame = frame
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

    // Something that knows where the beat is, asked once per frame.
    //
    // A registry rather than a setter, because a server-side detector and a
    // local one can both be running and a setter makes the second silently
    // replace the first. Nothing here detects beats: the frame already carries
    // the fields and this is what fills them.
    public open fun registerBeatProvider(provider: () -> BeatReading) {
        beatProviders += provider
    }

    // How much of the previous frame survives into this one, 0..1.
    //
    // The web sets this once on the shared analyser and every visualiser reading
    // from it inherits the value; here it is one object shared the same way. Zero
    // is the raw transform, which jitters on noise between transients and reads
    // as a broken display rather than as detail.
    public open fun smoothingTimeConstant(): Double = history.smoothingTimeConstant

    public open fun smoothingTimeConstant(value: Double) {
        history.smoothingTimeConstant = value
    }

    public open fun available(): Boolean = graph != null

    /**
     * The analysis window, in samples.
     *
     * A twelve-bar level meter wants a small transform and a fast response; a
     * scrolling spectrogram wants a large one and finer bins. The reference
     * lets a visualiser say which, and without it one had to be written
     * against whatever the graph happened to choose.
     *
     * Answers the reference's default when there is no graph to ask, so a
     * chrome building its bar count off this gets a usable number rather than
     * zero.
     */
    public open fun fftSize(): Int = graph?.fftSize() ?: DEFAULT_FFT_SIZE

    /**
     * Retune the analysis window.
     *
     * Clamped to the powers of two the reference allows rather than refused: a
     * visualiser asking for more resolution than a backend offers should still
     * draw something.
     */
    public open fun fftSize(samples: Int) {
        graph?.fftSize(nearestFftSize(samples))
    }

    // The tap is the plugin's, not the listeners'. A host tearing the plugin
    // down while a view still holds a subscription must still stop the graph
    // running an FFT into nothing.
    override fun dispose() {
        tap?.dispose()
        tap = null
        listeners.clear()
        beatProviders.clear()
        latest = null
        syntheticFrame = null
    }
}

// What a beat detector reports for one frame.
//
// Both nullable, because a detector that tracks tempo without calling
// individual hits and one that calls hits without a stable tempo are both
// ordinary. Neither is obliged to invent the other.
public data class BeatReading(
    val beat: Boolean? = null,
    val bpm: Double? = null,
)

// 256 to 4096, the reference's own range. The nearest allowed size rather than
// the next one up, so a caller asking for 1000 gets 1024 instead of a window
// twice the length it asked for.
private fun nearestFftSize(samples: Int): Int =
    FFT_SIZES.minByOrNull { size -> kotlin.math.abs(size - samples) } ?: DEFAULT_FFT_SIZE

private val FFT_SIZES: List<Int> = listOf(256, 512, 1024, 2048, 4096)
