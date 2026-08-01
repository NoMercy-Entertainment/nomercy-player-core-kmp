// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer

// Gain, silence and the selected dub, in libVLC's 0..100.
internal class VlcAudio(private val binding: LibVlcBinding, private val handle: Pointer) {

    // -1 until an audio output exists, which is after the first play rather
    // than after the first load. The caller decides what to do with that.
    fun volume(): Int = binding.audio.audioGetVolume(handle)

    fun volume(value: Int) {
        binding.audio.audioSetVolume(handle, value)
    }

    fun mute(muted: Boolean) {
        binding.audio.audioSetMute(handle, if (muted) 1 else 0)
    }

    fun track(): Int = binding.audio.audioGetTrack(handle)

    fun track(id: Int) {
        binding.audio.audioSetTrack(handle, id)
    }
}
