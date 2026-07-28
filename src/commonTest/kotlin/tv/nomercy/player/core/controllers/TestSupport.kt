// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.MediaBackend
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// lands off the main thread.
class ThrowsOnStopBackend : MediaBackend by FakeMediaBackend() {
    var stopWasAttempted: Boolean = false
        private set

    override fun stop() {
        stopWasAttempted = true
        error("the engine was already gone")
    }
}

fun newContext(): PlayerContext = PlayerContext(backend = FakeMediaBackend())

fun PlayerContext.fakeBackend(): FakeMediaBackend = backend as FakeMediaBackend

// Records events in emission order with full payload typing, so a test asserts
// the exact sequence rather than that something fired.
class EventLog {
    val names: MutableList<String> = mutableListOf()

    fun <T> record(ctx: PlayerContext, key: EventKey<T>, label: (T) -> String) {
        ctx.on(key) { names += label(it) }
    }

    fun <T> capture(ctx: PlayerContext, key: EventKey<T>): MutableList<T> {
        val received: MutableList<T> = mutableListOf()
        ctx.on(key) { received += it }
        return received
    }
}
