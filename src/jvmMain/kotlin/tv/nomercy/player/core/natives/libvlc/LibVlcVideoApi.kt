// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Library
import com.sun.jna.Pointer

// The picture: which stream, which captions, and where the frames go.
//
// The two callback registrations below are the whole reason this library needs
// no window and no toolkit. libVLC's default video outputs open a native window
// that paints above everything Compose draws; `vmem` hands each frame to a
// buffer instead, which is what lets the transport bar sit on top of the film.
internal interface LibVlcVideoApi : Library {

    fun videoGetTrack(player: Pointer): Int

    fun videoSetTrack(player: Pointer, track: Int): Int

    // "spu" is libVLC's word for a subpicture unit, which is a caption.
    fun videoGetSpu(player: Pointer): Int

    fun videoSetSpu(player: Pointer, spu: Int): Int

    // Called once per size, before the first frame of that size, to agree a
    // chroma and a stride.
    fun videoSetFormatCallbacks(
        player: Pointer,
        setup: VlcVideoFormatCallback,
        cleanup: VlcVideoCleanupCallback,
    )

    // Called per frame: lock hands libVLC somewhere to decode into, display says
    // the frame is complete.
    fun videoSetCallbacks(
        player: Pointer,
        lock: VlcVideoLockCallback,
        unlock: VlcVideoUnlockCallback,
        display: VlcVideoDisplayCallback,
        opaque: Pointer?,
    )
}
