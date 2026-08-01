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

// The engine's transport: what is loaded, and whether it is running.
internal interface LibVlcPlayerApi : Library {

    fun mediaPlayerNew(instance: Pointer): Pointer?

    fun mediaPlayerRelease(player: Pointer)

    // Retains the item, so the caller's own reference can be dropped straight
    // after. A player that outlived the reference count would be reading freed
    // memory on its next frame.
    fun mediaPlayerSetMedia(player: Pointer, media: Pointer?)

    // Retains what it answers. Every caller has to release it, and one that
    // forgets leaks the whole demuxer rather than a handle.
    fun mediaPlayerGetMedia(player: Pointer): Pointer?

    fun mediaPlayerPlay(player: Pointer): Int

    fun mediaPlayerSetPause(player: Pointer, pause: Int)

    fun mediaPlayerStop(player: Pointer)
}
