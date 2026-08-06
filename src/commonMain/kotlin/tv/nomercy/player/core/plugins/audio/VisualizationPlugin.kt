// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest

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

    public companion object Manifest : PluginManifest {
        // The web's base plugin carries this id and its subclasses override it
        // — a consumer's own visualiser ships as `fillz:winamp-classic`. The
        // base here carried none at all, so `visualization` did not exist in
        // the native trio's surface even though every piece of it did.
        override val id: String = "visualization"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

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

        subscription = spectrum.onFrame { frame ->
            latestFrame = frame
            if (enabled()) renderGuarded(frame)
        }
    }

    // A renderer that throws used to take the frame subscription with it, so a
    // visualiser with one bad branch went dark for the rest of the session and
    // nothing said why. Reported once and stopped, which is the web's answer
    // too: a buggy author must not storm the error bus every frame.
    private fun renderGuarded(frame: VisualizationFrame) {
        try {
            render(frame)
        }
        catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
            report(
                VisualizationErrorCodes.RENDER_FAILED,
                "render() threw — stopping this visualiser so a buggy author cannot storm the error bus every frame.",
                severity = Severity.ERROR,
                cause = cause,
            )
            stop()
        }
    }

    public open fun stop() {
        subscription?.dispose()
        subscription = null
    }

    public open fun running(): Boolean = subscription != null

    /**
     * The frame this visualiser last drew, or null before the first one.
     *
     * The reference keeps it so a host can read what is on screen without
     * subscribing a second time — a still for a thumbnail, a level meter drawn
     * elsewhere, a test asserting what was rendered. Only the spectrum plugin
     * exposed one here, and a consumer holding a VisualizationPlugin had no way
     * to ask it about its own last frame.
     */
    public open fun currentFrame(): VisualizationFrame? = latestFrame

    private var latestFrame: VisualizationFrame? = null

    override fun dispose() {
        stop()
    }
}
