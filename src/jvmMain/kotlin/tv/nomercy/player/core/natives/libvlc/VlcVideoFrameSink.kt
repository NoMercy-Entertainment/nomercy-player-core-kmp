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
}
