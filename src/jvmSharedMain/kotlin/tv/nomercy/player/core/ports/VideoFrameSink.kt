// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.nio.ByteBuffer

/**
 * Where a desktop engine's decoded picture goes.
 *
 * The same three calls libVLC's sink has always had, with the engine's name
 * taken off. Two engines now produce frames on this desktop and the view that
 * draws them does not care which — so the surface takes this, and
 * `VlcVideoFrameSink` is one implementation of it rather than the shape
 * everything else has to match.
 *
 * BGRA, four bytes a pixel, no padding between rows. libVLC's RV32 on a
 * little-endian machine and mpv's `bgra` are the same bytes; picking a
 * different order for the new engine would mean a picture with its reds and
 * blues swapped on whichever one lost the argument.
 */
public interface VideoFrameSink {

    /**
     * The byte order this sink wants its pixels in.
     *
     * Asked rather than assumed, because the answer is not the same on every
     * platform and getting it wrong is invisible in code and obvious on screen.
     * Skia on a little-endian desktop and libVLC's RV32 both want BGRA;
     * Android's ARGB_8888 is RGBA in memory. An engine that renders BGRA into
     * an Android bitmap produces a picture with its reds and blues exchanged,
     * which reads like a colour-management problem and is a one-word format
     * string. Defaulted to the desktop's order so no existing sink changes.
     */
    public val pixelOrder: PixelOrder get() = PixelOrder.BGRA

    /** A new picture size, before the first frame of it. */
    public fun format(width: Int, height: Int)

    /** One complete frame, positioned at zero. */
    public fun display(picture: ByteBuffer)

    /**
     * Forget the picture, when a new item is opened and before it has decoded
     * anything.
     *
     * Defaulted, because a sink with nothing to forget is not broken. Nothing
     * called this once, and the canvas kept the last frame of the previous item
     * — the wrong film on screen with the right title, the right audio and a
     * running clock.
     */
    public fun clear() {
    }
}

/**
 * A desktop engine that can hand its picture to a sink.
 *
 * Both engines here decode into a buffer rather than a window, and the view
 * that draws them should not have to know which one it has. Without this the
 * surface named one engine's type and an mpv backend could play with nowhere to
 * draw — which is the state the frame path was in for exactly one commit.
 */
/**
 * The two four-byte orders a sink on any of our platforms asks for.
 *
 * Named by the order the bytes sit in memory, not by the order a constant
 * spells them: the fourth byte is padding an engine leaves alone, so `bgr0`
 * and `rgb0` are what mpv is asked for and what the sinks receive.
 */
public enum class PixelOrder(public val mpvSwFormat: String) {
    BGRA("bgr0"),
    RGBA("rgb0"),
}

public interface FrameSourceBackend {

    /**
     * Attached once, before anything plays. Both engines pick a video output
     * when they first open a file, and a sink attached after that moment takes
     * effect on the NEXT file rather than this one.
     */
    public fun videoFrameSink(sink: VideoFrameSink)
}

