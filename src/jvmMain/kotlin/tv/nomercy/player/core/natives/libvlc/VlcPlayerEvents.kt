// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

// The seven things libVLC says that anything above this binding acts on.
//
// Seven of the thirty it emits. The rest — corked, scrambled, title changed,
// elementary stream added — have no consumer in this library, and attaching to
// an event nobody listens to is a native callback per frame for nothing.
internal interface VlcPlayerEvents {

    fun playing()

    fun paused()

    fun ended()

    fun errored()

    fun timeChanged(millis: Long)

    fun lengthChanged(millis: Long)

    // libVLC's cache fullness, 0..100. Zero is the moment it has run dry.
    fun buffering(cache: Float)
}
