// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer

// The transport, in libVLC's own units.
//
// Milliseconds and 0..100 gains, unconverted. The conversion to the library's
// seconds-and-0..1 contract happens once, in the backend, rather than being
// half-done here and half-done there.
internal class VlcPlayback(private val binding: LibVlcBinding, private val handle: Pointer) {

    fun play() {
        binding.player.mediaPlayerPlay(handle)
    }

    fun pause(paused: Boolean) {
        binding.player.mediaPlayerSetPause(handle, if (paused) 1 else 0)
    }

    fun stop() {
        binding.player.mediaPlayerStop(handle)
    }

    fun time(): Long = binding.status.mediaPlayerGetTime(handle)

    fun time(millis: Long) {
        binding.status.mediaPlayerSetTime(handle, millis)
    }

    fun length(): Long = binding.status.mediaPlayerGetLength(handle)

    fun rate(): Float = binding.status.mediaPlayerGetRate(handle)

    fun rate(value: Float) {
        binding.status.mediaPlayerSetRate(handle, value)
    }

    fun playing(): Boolean = binding.status.mediaPlayerIsPlaying(handle) != 0

    fun playable(): Boolean = binding.status.mediaPlayerWillPlay(handle) != 0
}
