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
import com.sun.jna.ptr.PointerByReference

// One item: how it is opened, what options travel with it, and what streams it
// turned out to contain.
internal interface LibVlcMediaApi : Library {

    // A URL. libVLC parses the scheme itself, which is why a plain Windows path
    // handed to this one is opened as a file called "C" on a host called nothing.
    fun mediaNewLocation(instance: Pointer, mrl: String): Pointer?

    // A filesystem path, in the platform's own shape.
    fun mediaNewPath(instance: Pointer, path: String): Pointer?

    // Applied to this item only, and only before it is opened. libVLC reads them
    // while it builds the demuxer chain, so an option added after playback has
    // started is an option for the next item.
    fun mediaAddOption(media: Pointer, option: String)

    fun mediaRelease(media: Pointer)

    // Fills [tracks] with an array of libvlc_media_track_t pointers and answers
    // how many. The array belongs to libVLC until it is handed back below.
    fun mediaTracksGet(media: Pointer, tracks: PointerByReference): Int

    fun mediaTracksRelease(tracks: Pointer, count: Int)
}
