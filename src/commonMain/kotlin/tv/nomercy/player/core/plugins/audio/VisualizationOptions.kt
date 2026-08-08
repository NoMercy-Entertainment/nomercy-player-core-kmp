// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

/**
 * How a visualiser draws.
 *
 * [clearBeforeRender] is off by default and that is the recommendation, not an
 * oversight: the canvas plugin clears once for the whole stack, and a visualiser
 * clearing on its own erases whatever was drawn under it. Turning it on is for
 * the one visualiser that genuinely needs its own pass.
 */
public data class VisualizationOptions(
    /** Clear the canvas before this visualiser's own render. */
    val clearBeforeRender: Boolean = false,

    /**
     * What drives the render loop.
     *
     * Only [VisualizationTick.FRAME] is active; the web declares the other and
     * does not read it yet, and the port says so rather than implementing a
     * behaviour the original does not have.
     */
    val tick: VisualizationTick = VisualizationTick.FRAME,
)

/** Driven by the canvas plugin's loop, or polled when a frame is asked for. */
public enum class VisualizationTick(public val id: String) {
    FRAME("frame"),
    TIME("time"),
}
