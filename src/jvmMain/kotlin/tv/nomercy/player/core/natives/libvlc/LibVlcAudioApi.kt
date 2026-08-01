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

// Gain, silence and which dub is playing.
internal interface LibVlcAudioApi : Library {

    // 0..100, and -1 before an audio output exists — which is after the first
    // play rather than after the first load.
    fun audioGetVolume(player: Pointer): Int

    fun audioSetVolume(player: Pointer, volume: Int): Int

    fun audioGetMute(player: Pointer): Int

    fun audioSetMute(player: Pointer, mute: Int)

    fun audioGetTrack(player: Pointer): Int

    fun audioSetTrack(player: Pointer, track: Int): Int
}
