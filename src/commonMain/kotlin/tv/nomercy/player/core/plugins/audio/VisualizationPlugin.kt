// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.Plugin

// The base a visualiser is written against.
//
// Subscribing, unsubscribing and surviving a torn-down view are the same three
// problems for every visualiser, and each one is a leak if it is written again
// slightly differently. Only render() differs, so only render() is abstract.
//
// Open rather than sealed, and every member open with it. A consumer writing
// their own visualiser is the point of this class existing, and the library
// never makes a member final to stop someone doing something.
public abstract class VisualizationPlugin(
    private val spectrum: SpectrumPlugin,
) : Plugin<Unit>() {

    private var subscription: Subscription? = null

    // Called for every frame while the visualiser is running. The frame is
    // already normalised, so an implementation binds to it directly rather than
    // discovering the scale by experiment.
    public abstract fun render(frame: VisualizationFrame)

    // Idempotent, because a view that is attached, detached and attached again
    // calls this twice, and a second subscription would render every frame
    // twice — visible as a visualiser that moves at double speed after the
    // first rotation.
    public open fun start() {
        if (subscription != null) return

        subscription = spectrum.onFrame { frame -> if (enabled()) render(frame) }
    }

    public open fun stop() {
        subscription?.dispose()
        subscription = null
    }

    public open fun running(): Boolean = subscription != null

    override fun dispose() {
        stop()
    }
}
