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
import com.sun.jna.StringArray

// libVLC's own lifetime: the engine instance everything else is made from.
//
// Split from the rest of the binding by subject rather than by size. libVLC's C
// API is one flat namespace of about a hundred functions and the six interfaces
// in this package take the fraction of it this library calls, grouped the way
// the header groups them — instance, media, player, audio, video, events. A
// reader looking for "how does volume work" opens one file of six methods.
internal interface LibVlcCoreApi : Library {

    // Null when libVLC cannot start, which on a machine missing its plugin
    // directory is the first thing that happens.
    fun new(argc: Int, argv: StringArray): Pointer?

    fun release(instance: Pointer)

    // The last error libVLC recorded on this thread, or null. Worth asking only
    // immediately after a call answered null.
    fun errmsg(): String?
}
