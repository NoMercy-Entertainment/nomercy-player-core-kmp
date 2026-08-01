// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer
import com.sun.jna.StringArray

/**
 * A running libVLC engine, and everything made from it.
 *
 * One of these owns the plugin cache, the logging and every player built out of
 * it, so two in a process is two of all three — which is why the crossfade hands
 * one instance to both of its players rather than building a second.
 *
 * Constructing it is what binds libVLC. It throws when there is none: an
 * [UnsatisfiedLinkError] when the library cannot be found or loaded, and an
 * [IllegalStateException] when it loaded but could not start, which on a desktop
 * is nearly always a plugin directory that did not arrive with it.
 */
public class VlcInstance {

    internal val binding: LibVlcBinding = LibVlcLoader.require()

    internal val handle: Pointer = binding.core.new(0, NO_ARGUMENTS)
        ?: error("libVLC loaded but would not start: ${binding.core.errmsg() ?: "no reason given"}")

    private var released: Boolean = false

    /**
     * Every player made from this instance must be released first. libVLC frees
     * the engine's own threads here, and a player still holding one of them is a
     * crash rather than a leak.
     */
    public fun release() {
        if (released) return
        released = true
        binding.core.release(handle)
    }

    internal fun newPlayer(): Pointer = binding.player.mediaPlayerNew(handle)
        ?: error("libVLC would not create a player: ${binding.core.errmsg() ?: "no reason given"}")

    private companion object {
        // What libVLC is started with, which is nothing. Every option this
        // library needs travels with the item instead, because an option on the
        // instance applies to a crossfade's second engine as much as its first.
        val NO_ARGUMENTS = StringArray(emptyArray<String>())
    }
}
