// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.PlaylistItem

private const val MILLIS_PER_SECOND = 1_000.0

// One moment during a transition. [fraction] is progress through the overlap
// window, 0 at the start and 1 at the end.
public data class TransitionContext(
    val currentTime: Double,
    val duration: Double,
    val outgoingItem: PlaylistItem,
    val incomingItem: PlaylistItem,
    val fraction: Double,
)

// How the player gets from one item to the next.
//
// Separate from PreloadStrategy because they answer different questions:
// preload decides what to fetch, this decides what the change sounds and looks
// like. A consumer replaces either without touching the other.
public interface TransitionStrategy {
    public fun shouldTransition(context: PreloadContext): Boolean
    public fun tick(context: TransitionContext, backend: TransitionBackend?)
    public fun start(outgoing: PlaylistItem, incoming: PlaylistItem, backend: TransitionBackend?)
    public fun complete(from: PlaylistItem, to: PlaylistItem)
    public fun cancel(reason: String)
}

// Overlaps the two items and rides the gain curve across the window. The music
// default.
public class CrossfadeTransitionStrategy(
    private val leadSeconds: Double = 3.0,
    private val tailSeconds: Double = 3.0,
    public val curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
) : TransitionStrategy {

    // How long the engine should be told to fade for. Separate from
    // [leadSeconds]: when to start and how long to take are different
    // decisions, and a long lead with a short fade is a legitimate setup.
    public val crossfadeMs: Long = (tailSeconds * MILLIS_PER_SECOND).toLong()

    override fun shouldTransition(context: PreloadContext): Boolean {
        if (context.nextItem == null || context.duration <= 0.0) return false
        return context.currentTime >= context.duration - leadSeconds
    }

    // A backend that cannot play two things at once is left alone rather than
    // failing: the result is a hard cut, which is the honest degradation.
    override fun tick(context: TransitionContext, backend: TransitionBackend?) {
        if (backend == null || !backend.supportsCrossfade()) return
        backend.secondaryGain(curve.gain(context.fraction).toFloat())
    }

    override fun start(outgoing: PlaylistItem, incoming: PlaylistItem, backend: TransitionBackend?): Unit = Unit
    override fun complete(from: PlaylistItem, to: PlaylistItem): Unit = Unit
    override fun cancel(reason: String): Unit = Unit
}

// Cuts at the natural end with no overlap. The video default, and correct
// there: two video streams overlapping is a dissolve nobody asked for.
public class GaplessTransitionStrategy : TransitionStrategy {
    override fun shouldTransition(context: PreloadContext): Boolean = false
    override fun tick(context: TransitionContext, backend: TransitionBackend?): Unit = Unit
    override fun start(outgoing: PlaylistItem, incoming: PlaylistItem, backend: TransitionBackend?): Unit = Unit
    override fun complete(from: PlaylistItem, to: PlaylistItem): Unit = Unit
    override fun cancel(reason: String): Unit = Unit
}
