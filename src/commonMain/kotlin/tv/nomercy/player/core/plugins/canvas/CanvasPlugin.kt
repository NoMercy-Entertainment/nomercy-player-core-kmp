// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.canvas

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.PluginOptionField

public enum class CompositeMode {
    // Each frame starts blank. What a renderer did not draw this frame is gone.
    CLEAR,

    // Frames accumulate. For layered effects where one renderer adds to what
    // another left behind.
    COMPOSITE,
}

public data class CanvasOptions(
    // Frames per second the loop is capped to. The surface's own refresh rate
    // is still the ceiling; this only lowers it.
    val fps: Int = DEFAULT_CANVAS_FPS,

    val compositeMode: CompositeMode = CompositeMode.CLEAR,
)

/**
 * The shared surface and frame loop every visualiser draws through.
 *
 * The same `canvas` id the web plugin has, and the same bargain: without it
 * registered there is no loop and no per-frame cost at all. What it owns is the
 * part that is identical for every visualiser and wrong in a slightly different
 * way each time somebody writes it again — the fps cap, the delta between
 * accepted frames, the order renderers run in, and what happens when one of
 * them throws.
 *
 * What it does NOT own is the surface itself. The web plugin creates a
 * `<canvas>` because a browser hands it one; here the drawing surface belongs
 * to the toolkit that is already drawing — a Compose `DrawScope`, a SwiftUI
 * `GraphicsContext` — and neither can be held past its own draw pass. So the
 * consumer reports the surface with [mount] and [size], watches [ticks] to know
 * when to redraw, and calls [draw] from inside the pass it already has.
 *
 *     val canvas = CanvasPlugin<DrawScope>()
 *     player.plugins.register(canvas)
 *
 *     val tick by canvas.ticks.collectAsState()
 *     Canvas(Modifier.onSizeChanged { canvas.size(it.width, it.height) }) {
 *         tick.let { canvas.draw(this) }
 *     }
 *
 * [S] is whatever that surface is. The type travels to the renderers unchanged,
 * so a visualiser binds to the real drawing API rather than to a lowest common
 * denominator invented here.
 */
public open class CanvasPlugin<S : Any>(
    opts: CanvasOptions = CanvasOptions(),
) : Plugin<CanvasOptions>() {

    // Held rather than fixed: the fps cap is the option most worth turning down
    // while watching what it costs, which means the loop has to read it again.
    private var opts: CanvasOptions = opts

    public companion object Manifest : PluginManifest {
        override val id: String = "canvas"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: CanvasOptions get() = opts

    override fun optionFields(): List<PluginOptionField> = listOf(
        PluginOptionField.Number(
            key = "fps",
            label = "Frame cap",
            value = opts.fps.toDouble(),
            min = MIN_CANVAS_FPS,
            max = MAX_CANVAS_FPS,
            apply = { chosen -> opts = opts.copy(fps = chosen.toInt()) },
        ),
        PluginOptionField.Choice(
            key = "compositeMode",
            label = "Between frames",
            value = opts.compositeMode.name,
            choices = CompositeMode.entries.map { mode -> mode.name },
            apply = { chosen ->
                opts = opts.copy(compositeMode = CompositeMode.valueOf(chosen))
            },
        ),
    )

    private val renderers: MutableList<CanvasRenderer<S>> = mutableListOf()

    private val surface: MutableStateFlow<CanvasSize?> = MutableStateFlow(null)
    private val frames: MutableStateFlow<Long> = MutableStateFlow(0L)

    private var loop: Job? = null
    private var elapsedMs: Double = 0.0
    private var lastAcceptedMs: Double? = null
    private var lastFrame: CanvasFrame = CanvasFrame(deltaMs = 0.0, timeMs = 0.0)

    /** The surface's size, or null before a consumer has reported one. */
    public val bounds: StateFlow<CanvasSize?> = surface.asStateFlow()

    /**
     * Counts accepted frames. A consumer redraws when this changes; ticks the
     * fps cap rejected never move it, so an fps of 24 costs 24 redraws a second
     * and not 60 that mostly draw the same thing.
     */
    public val ticks: StateFlow<Long> = frames.asStateFlow()

    /** How each frame starts, so a consumer knows whether to clear before [draw]. */
    public val compositeMode: CompositeMode get() = opts.compositeMode

    /**
     * The surface is on screen at this size. Starts the loop.
     *
     * Idempotent: a view attached, detached and attached again calls this
     * twice, and a second loop would run every renderer twice per frame —
     * visible as a visualiser that moves at double speed after the first
     * rotation.
     */
    public fun mount(width: Double, height: Double, pixelRatio: Double = 1.0) {
        val size = CanvasSize(width, height, pixelRatio)
        surface.value = size
        emit(CanvasEvents.Mounted, size)
        start()
    }

    /** The surface is gone. Stops the loop and keeps the renderers. */
    public fun unmount() {
        stop()
        surface.value = null
    }

    /** A new size for the surface already mounted. */
    public fun size(width: Double, height: Double, pixelRatio: Double = 1.0) {
        val size = CanvasSize(width, height, pixelRatio)
        if (surface.value == size) return

        surface.value = size
        emit(CanvasEvents.Resized, size)
    }

    /**
     * Add a renderer. It runs once per accepted frame, in the order it was
     * added, inside the consumer's draw pass.
     *
     * Returns the way to remove it, so a caller that owns one renderer does not
     * have to keep the reference to take it away again.
     */
    public fun addRenderer(renderer: CanvasRenderer<S>): () -> Unit {
        renderers += renderer
        return { removeRenderer(renderer) }
    }

    public fun removeRenderer(renderer: CanvasRenderer<S>) {
        renderers.remove(renderer)
    }

    /**
     * Run every renderer against the surface, in order.
     *
     * Called from the consumer's draw pass, which is the only place [S] is
     * valid. A renderer that throws is reported and removed rather than left
     * in: one shared surface hosts several independent renderers, and one bad
     * author must not silence the others or stop the loop.
     */
    public fun draw(target: S) {
        if (!enabled()) return

        val frame: CanvasFrame = lastFrame
        for (renderer in renderers.toList()) {
            runCatching { renderer.render(target, frame.deltaMs, frame.timeMs) }
                .onFailure { cause: Throwable -> dropFailed(renderer, cause) }
        }
    }

    override fun dispose() {
        stop()
        renderers.clear()
        surface.value = null
    }

    private fun start() {
        if (loop != null) return

        loop = frame { deltaMs: Long -> tick(deltaMs) }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
        lastAcceptedMs = null
    }

    // The fps cap, carrying the overshoot remainder rather than anchoring to the
    // tick that crossed it. A plain anchor loses every frame the scheduler
    // delivers a hair early and collapses to half rate when the cap matches the
    // display cadence — which is the default.
    //
    // Internal rather than private because the loop's clock is the platform's
    // monotonic one and a test's is virtual: driving the cap through the loop
    // would measure the scheduler, not the cap.
    internal fun tick(deltaMs: Long) {
        elapsedMs += deltaMs
        val minFrameMs: Double = MS_PER_SECOND / opts.fps.coerceAtLeast(1)
        val previous: Double? = lastAcceptedMs

        val renderDeltaMs: Double
        if (previous == null) {
            renderDeltaMs = deltaMs.toDouble()
            lastAcceptedMs = elapsedMs
        } else {
            renderDeltaMs = elapsedMs - previous
            if (renderDeltaMs < minFrameMs) return
            lastAcceptedMs = elapsedMs - renderDeltaMs % minFrameMs
        }

        lastFrame = CanvasFrame(deltaMs = renderDeltaMs, timeMs = elapsedMs)
        frames.value += 1
        emit(CanvasEvents.Frame, lastFrame)
    }

    private fun dropFailed(renderer: CanvasRenderer<S>, cause: Throwable) {
        removeRenderer(renderer)
        report(
            code = CANVAS_RENDERER_FAILED,
            message = "A registered renderer threw while drawing — removing it so the rest keep running.",
            severity = Severity.ERROR,
            cause = cause,
        )
    }
}

/**
 * What one renderer does with the surface for one frame.
 *
 * [deltaMs] is since the previous accepted frame and [timeMs] is since the loop
 * started, both of which a renderer integrating movement needs and neither of
 * which it can work out on its own from inside a draw pass.
 */
public fun interface CanvasRenderer<S : Any> {
    public fun render(surface: S, deltaMs: Double, timeMs: Double)
}

/** The web's sixty. */
public const val DEFAULT_CANVAS_FPS: Int = 60

// What an editor offers. One frame a second is still a loop, and past 120 the
// surface's own refresh rate is the ceiling anyway.
private const val MIN_CANVAS_FPS: Double = 1.0
private const val MAX_CANVAS_FPS: Double = 120.0

public const val CANVAS_RENDERER_FAILED: String = "canvas:render/renderer-failed"

private const val MS_PER_SECOND = 1_000.0
