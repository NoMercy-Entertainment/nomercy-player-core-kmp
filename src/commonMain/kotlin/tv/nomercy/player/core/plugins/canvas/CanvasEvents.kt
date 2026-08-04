// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.canvas

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.pluginEventKey

// What the canvas plugin announces.
//
// The names it emits under and the names a consumer subscribes to, both given
// rather than spelled by hand, because a listener built from the wrong one is
// not an error, it is a listener that never fires.
public object CanvasEvents {

    public val Mounted: EventKey<CanvasSize> = EventKey("mounted")

    public val Resized: EventKey<CanvasSize> = EventKey("resized")

    public val Frame: EventKey<CanvasFrame> = EventKey("frame")

    public val MountedOnPlayer: EventKey<CanvasSize> =
        pluginEventKey(CanvasPlugin.Manifest, "mounted")

    public val ResizedOnPlayer: EventKey<CanvasSize> =
        pluginEventKey(CanvasPlugin.Manifest, "resized")

    public val FrameOnPlayer: EventKey<CanvasFrame> =
        pluginEventKey(CanvasPlugin.Manifest, "frame")
}

// The surface, in the units the consumer laid it out in.
//
// Logical rather than bitmap pixels, and the ratio is carried alongside instead
// of multiplied in, because a renderer that draws a two-pixel line wants two
// pixels on a phone and on a 4K panel. Baking the ratio into the size is how
// the same visualiser comes out hairline-thin on one screen and heavy on
// another.
public data class CanvasSize(
    val width: Double,
    val height: Double,
    val pixelRatio: Double = 1.0,
)

// One accepted frame. Ticks the fps cap rejected produce none of these.
//
// [deltaMs] is time since the previous ACCEPTED frame, not since the previous
// tick — a renderer integrating movement over it stays at the same speed
// whatever the cap is set to.
public data class CanvasFrame(
    val deltaMs: Double,
    val timeMs: Double,
)
