// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext
import kotlin.math.max

// How much of the screen the viewer cannot actually see.
//
// Televisions crop. A panel is sold on the picture reaching the edge of the
// glass, and to guarantee that it draws the image slightly larger than the
// screen and throws away the rest. How much is not published and is not the
// same twice, so anything at the edge is a gamble: a clock in the corner is
// missing its first digit on one set and fine on the next.
//
// Everything else has a version of the same problem under a different name, so
// the four numbers are the contract and the platforms disagree only about what
// to put in them.
public data class SafeAreaInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {

    // The larger of each edge, not the sum.
    //
    // Two sources of inset are two claims about the same edge rather than two
    // separate margins: a television that crops 48 and a system bar that wants
    // 30 both describe how far in it is safe to draw, and adding them pushes the
    // controls a long way into a picture nobody was hiding.
    public operator fun plus(other: SafeAreaInsets): SafeAreaInsets = SafeAreaInsets(
        left = max(left, other.left),
        top = max(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    public val isEmpty: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

// Five percent of a 1920 by 1080 picture, which is the figure the broadcast
// world settled on and every television is built to be safe within.
//
// Used because no Android version since API 21 will say what a given panel
// actually crops. It is a guess, but it is the guess the whole industry makes,
// and being slightly conservative costs a little space while being wrong the
// other way costs a control nobody can see.
public val DEFAULT_TV_OVERSCAN: SafeAreaInsets = SafeAreaInsets(
    left = 48f,
    top = 27f,
    right = 48f,
    bottom = 27f,
)

// What this platform says is safe to draw in.
public expect fun platformOverscan(context: PlatformContext): SafeAreaInsets
