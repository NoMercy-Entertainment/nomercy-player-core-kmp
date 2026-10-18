// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

// The reload a network failure schedules, and what happens while it waits.
class DelayedRecoveryReloadTest {

    private class FlakyBackend : FakeMediaBackend() {
        var refuseLoads: Boolean = false

        override suspend fun load(url: String, opts: LoadOptions) {
            if (refuseLoads) error("server is down")
            super.load(url, opts)
        }
    }

    private val networkDrop = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"

    @Test
    fun aPendingReloadIsDroppedOnceTheViewerMovedToAnotherItem() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.queue(listOf(TestItem("a"), TestItem("b")))
        player.setup()
        player.play()

        backend.fire(CanonicalBackendEvent.ERROR, networkDrop)
        player.next()
        advanceTimeBy(5_000)

        assertEquals(
            listOf("https://example.test/a", "https://example.test/b"),
            backend.loadedUrls,
        )
    }

    @Test
    fun aFailedReloadIsReportedThroughTheErrorPath() = runTest {
        val backend = FlakyBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()
        val errors: MutableList<PlayerErrorEvent> = mutableListOf()
        var backendErrors = 0
        player.on(CoreEvents.Error) { errors += it }
        player.on(CoreEvents.BackendError) { backendErrors += 1 }

        backend.refuseLoads = true
        backend.fire(CanonicalBackendEvent.ERROR, networkDrop)
        advanceTimeBy(5_000)

        assertEquals(listOf(CoreErrorCodes.LOAD_FAILED), errors.map { it.code })
        assertEquals(1, backendErrors)
    }
}
