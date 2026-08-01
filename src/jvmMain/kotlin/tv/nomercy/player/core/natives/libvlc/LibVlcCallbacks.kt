// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Callback
import com.sun.jna.Pointer

// The five function pointers libVLC calls back on, and one rule that governs all
// of them: whatever is handed in is only valid until the call returns. libVLC
// owns the memory, reuses it, and frees it the moment it has the return value —
// so anything worth keeping is copied out here, on this thread, before returning.
//
// The parameter lists are libVLC's, not this library's. They are the C
// signatures from vlc/libvlc_media_player.h and there is nothing to decompose:
// an argument fewer would be a different function than the one being bound.

// libvlc_callback_t. Fired on libVLC's own thread, one event at a time.
internal fun interface VlcEventCallback : Callback {
    fun onEvent(event: Pointer, userData: Pointer?)
}

// libvlc_video_format_cb. Answers how many planes the caller allocated, after
// writing the chroma, the stride and the row count libVLC should decode into.
internal fun interface VlcVideoFormatCallback : Callback {
    fun onFormat(
        opaque: Pointer,
        chroma: Pointer,
        width: Pointer,
        height: Pointer,
        pitches: Pointer,
        lines: Pointer,
    ): Int
}

// libvlc_video_cleanup_cb.
internal fun interface VlcVideoCleanupCallback : Callback {
    fun onCleanup(opaque: Pointer?)
}

// libvlc_video_lock_cb. Points libVLC at somewhere to decode into and answers a
// picture identifier, which this binding has no use for.
internal fun interface VlcVideoLockCallback : Callback {
    fun onLock(opaque: Pointer?, planes: Pointer): Pointer?
}

// libvlc_video_unlock_cb.
internal fun interface VlcVideoUnlockCallback : Callback {
    fun onUnlock(opaque: Pointer?, picture: Pointer?, planes: Pointer)
}

// libvlc_video_display_cb. The frame is complete when this is called.
internal fun interface VlcVideoDisplayCallback : Callback {
    fun onDisplay(opaque: Pointer?, picture: Pointer?)
}
