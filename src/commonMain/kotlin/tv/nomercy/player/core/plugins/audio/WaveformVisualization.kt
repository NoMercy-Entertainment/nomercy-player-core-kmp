// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

// The reference visualiser: the waveform, reduced to however many columns the
// view has room for.
//
// It ships for the same reason the web's does — a consumer writing their first
// visualiser should have one to read that does the awkward part. The awkward
// part is not the drawing; it is that a frame carries a few thousand samples
// and a view has a few hundred columns, and every visualiser has to decide what
// to do about that.
//
// This library draws nothing. It cannot: the toolkit is Compose on one platform
// and SwiftUI on another. What it can do is hand a chrome an array the width of
// its view, already reduced, so the platform layer only has to draw a line.
public open class WaveformVisualization(
    spectrum: SpectrumPlugin,
    // How many columns the view has. Given rather than guessed, because only
    // the chrome knows how wide it is and a fixed number is wrong on every
    // screen except the one it was chosen on.
    private val columns: Int = DEFAULT_COLUMNS,
) : VisualizationPlugin(spectrum) {

    public companion object Manifest : tv.nomercy.player.core.plugin.PluginManifest {
        override val id: String = "waveform-visualization"
        override val version: String = "1.0.0"
    }

    override val manifest: tv.nomercy.player.core.plugin.PluginManifest get() = Manifest

    private var reduced: DoubleArray = DoubleArray(0)

    private var onColumns: ((DoubleArray) -> Unit)? = null

    // The latest reduction, for a chrome drawing on its own clock.
    public open fun columns(): DoubleArray = reduced

    // Or be told, for a chrome that redraws when the audio says so.
    public open fun onColumns(fn: (DoubleArray) -> Unit) {
        onColumns = fn
    }

    override fun render(frame: VisualizationFrame) {
        reduced = reduce(frame.waveform, columns)
        onColumns?.invoke(reduced)
    }

    // The largest excursion in each column's slice, not the average.
    //
    // An average of a waveform is near zero however loud it is — the positive
    // and negative halves cancel — so an averaging reduction draws a flat line
    // through the middle of the loudest music. The peak is what the eye reads
    // as amplitude, and it is what every audio editor draws.
    protected open fun reduce(samples: DoubleArray, into: Int): DoubleArray {
        if (into <= 0 || samples.isEmpty()) return DoubleArray(0)
        if (samples.size <= into) return samples.copyOf()

        val perColumn: Int = samples.size / into
        return DoubleArray(into) { column ->
            val start: Int = column * perColumn
            // The last column takes whatever is left over, so a sample count
            // that does not divide evenly is not silently dropped off the end.
            val end: Int = if (column == into - 1) samples.size else start + perColumn
            peakBetween(samples, start, end)
        }
    }

    private fun peakBetween(samples: DoubleArray, start: Int, end: Int): Double {
        var peak = 0.0
        for (index in start until end) {
            val size: Double = if (samples[index] < 0.0) -samples[index] else samples[index]
            if (size > peak) peak = size
        }
        return peak
    }
}

// Wide enough to look like a waveform on a phone, cheap enough to reduce every
// frame. A chrome that knows its own width should say so.
private const val DEFAULT_COLUMNS = 128
