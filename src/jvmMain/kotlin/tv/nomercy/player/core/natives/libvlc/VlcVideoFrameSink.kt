// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import java.nio.ByteBuffer

/**
 * Where a decoded frame goes.
 *
 * The desktop draws video by handing libVLC a buffer rather than a window. A
 * native window on the desktop paints ABOVE everything the toolkit draws, so an
 * embedded surface would put the picture over the transport bar with no
 * z-ordering able to move it — the two are not in the same compositor at all.
 *
 * Implementations are called on libVLC's own video thread, and both calls carry
 * the same rule: the buffer is reused for every frame, so a picture worth
 * keeping is copied out before [display] returns. Holding it instead draws
 * whatever the decoder happens to be writing next.
 */
public interface VlcVideoFrameSink {

    /**
     * A new picture size, before the first frame of it. The pixels arrive as
     * BGRA — libVLC's RV32 on a little-endian machine — packed with no padding
     * between rows.
     */
    public fun format(width: Int, height: Int)

    /** One complete frame, positioned at zero. */
    public fun display(picture: ByteBuffer)

    /**
     * Forget the picture. Called when a new item is opened, before it has
     * decoded anything.
     *
     * Nothing did this, so the canvas kept the last frame of the previous item
     * until the next one produced its own — and an item that produces none
     * leaves the wrong film on screen indefinitely, with the right title, the
     * right audio and a running clock. It reads as a switching bug and is
     * really the absence of an erase.
     *
     * Defaulted, because a sink that has nothing to forget is not broken.
     */
    public fun clear() {
    }
}
