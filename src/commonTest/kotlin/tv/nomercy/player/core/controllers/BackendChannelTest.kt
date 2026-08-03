// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

// The engine's own channel, which was declared and never spoken.
//
// backend:loading, backend:loaded, backend:waiting and backend:stalled were in
// CoreEvents and in CoreEvents.all, and nothing in the whole of commonMain
// emitted one. A consumer subscribing heard nothing, ever, while the registry
// said the event existed - which is worse than an absent event, because it
// reads as a working feature.
class BackendChannelTest {

    private fun player(backend: FakeMediaBackend): ComposedPlayer =
        ComposedPlayer(backend = backend).also { it.queue(listOf(TestItem("a"))) }

    @Test
    fun theEngineAnnouncesItIsLoadingAndThenLoaded() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<String> = mutableListOf()
        subject.on(CoreEvents.BackendLoading) { seen += "loading" }
        subject.on(CoreEvents.BackendLoaded) { seen += "loaded" }

        backend.fire(CanonicalBackendEvent.LOAD_START)
        backend.fire(CanonicalBackendEvent.LOADED_METADATA)

        assertEquals(listOf("loading", "loaded"), seen)
    }

    @Test
    fun andThatItIsWaitingForData() = runTest {
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<String> = mutableListOf()
        subject.on(CoreEvents.BackendWaiting) { seen += "waiting" }

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(listOf("waiting"), seen)
    }

    @Test
    fun andThatItHasStalledWhereItStalled() = runTest {
        // The position rides along, because "it stalled" without a time is a
        // report a consumer cannot act on.
        val backend = FakeMediaBackend()
        val subject: ComposedPlayer = player(backend)
        val seen: MutableList<Double> = mutableListOf()
        subject.on(CoreEvents.BackendStalled) { seen += it.time }

        backend.fire(CanonicalBackendEvent.STALLED)

        assertEquals(1, seen.size)
    }
}
