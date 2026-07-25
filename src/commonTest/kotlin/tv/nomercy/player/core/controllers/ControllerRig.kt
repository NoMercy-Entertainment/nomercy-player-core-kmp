// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerPhase

fun items(vararg ids: String): List<PlaylistItem> = ids.map { TestItem(it) }

// The three controllers as the player composes them, so a test drives the same
// wiring the real thing has rather than a controller in isolation.
class Rig {
    val ctx: PlayerContext = newContext()
    val queue: QueueController = QueueController(ctx)
    val transport: TransportController = TransportController(ctx, queue)
    val volume: VolumeController = VolumeController(ctx)

    val backend: FakeMediaBackend get() = ctx.fakeBackend()

    init {
        queue.transport = transport
        queue.wireQueue()
    }

    fun ready(): Rig = apply { ctx.transitionPhase(PlayerPhase.READY) }
}
