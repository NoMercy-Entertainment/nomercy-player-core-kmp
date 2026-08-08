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
public interface FrameSourceBackend {

    /**
     * Attached once, before anything plays. Both engines pick a video output
     * when they first open a file, and a sink attached after that moment takes
     * effect on the NEXT file rather than this one.
     */
    public fun videoFrameSink(sink: VideoFrameSink)
}

