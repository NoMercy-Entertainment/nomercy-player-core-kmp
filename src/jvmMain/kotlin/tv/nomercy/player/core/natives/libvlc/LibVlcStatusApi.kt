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

// Where the engine is and how fast it is going.
//
// Milliseconds throughout, and -1 for "not known yet" on both the position and
// the length — libVLC does not know either until it has read the container.
internal interface LibVlcStatusApi : Library {

    fun mediaPlayerGetTime(player: Pointer): Long

    fun mediaPlayerSetTime(player: Pointer, time: Long)

    fun mediaPlayerGetLength(player: Pointer): Long

    fun mediaPlayerGetRate(player: Pointer): Float

    fun mediaPlayerSetRate(player: Pointer, rate: Float): Int

    fun mediaPlayerIsPlaying(player: Pointer): Int

    // libVLC's word for "there is something loaded that could play".
    fun mediaPlayerWillPlay(player: Pointer): Int
}
