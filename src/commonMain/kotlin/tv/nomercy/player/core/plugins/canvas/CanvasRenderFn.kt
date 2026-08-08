// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.canvas

/**
 * One frame of a visualiser's drawing.
 *
 * Generic over the drawing surface rather than naming one, because the web hands
 * a `CanvasRenderingContext2D` and there is no such thing here: Compose draws
 * into a `DrawScope`, SwiftUI into a `GraphicsContext`. The two arguments after
 * it are the same on every platform and are what a visualiser actually needs.
 *
 * BOTH [deltaMs] and [time] are passed, and neither is redundant. Animation that
 * moves at a rate uses the delta and stays correct when a frame is late;
 * animation that is a function of the clock — a sweep, a pulse on the beat —
 * uses the absolute time and stays in step with the audio. Deriving either from
 * the other accumulates the drift the other one exists to avoid.
 */
public fun interface CanvasRenderFn<C> {
    public fun render(context: C, deltaMs: Double, time: Double)
}
