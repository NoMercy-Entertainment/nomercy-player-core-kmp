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

// libVLC's own notifications, which is how anything above learns the engine
// changed its mind without polling it.
internal interface LibVlcEventApi : Library {

    fun mediaPlayerEventManager(player: Pointer): Pointer?

    fun eventAttach(
        manager: Pointer,
        eventType: Int,
        callback: VlcEventCallback,
        userData: Pointer?,
    ): Int

    fun eventDetach(
        manager: Pointer,
        eventType: Int,
        callback: VlcEventCallback,
        userData: Pointer?,
    )
}
