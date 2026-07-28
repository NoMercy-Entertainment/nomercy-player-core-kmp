// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The stand-in has its own tests, because it is shipped.
//
// A fake nobody checks is a fake that quietly stops behaving like the thing it
// stands for, and every suite written against it then passes for the wrong
// reason. This one is published, so somebody else's tests are downstream of it
// too.
class FakeMediaBackendTest {

    @Test
    fun loadingReportsWhatARealEngineReportsOnceItHasReadTheContainer() = runTest {
        val backend = FakeMediaBackend()
        val seen: MutableList<String> = mutableListOf()
        for (name in listOf(CanonicalBackendEvent.LOADED_METADATA, CanonicalBackendEvent.CAN_PLAY)) {
            backend.on(name) { seen += name }
        }

        backend.load("https://example.test/a")

        assertEquals(listOf(CanonicalBackendEvent.LOADED_METADATA, CanonicalBackendEvent.CAN_PLAY), seen)
    }

    @Test
    fun playingConfirmsItselfOnTheEventStream() = runTest {
        // play and playing are different moments and the bridge exists because
        // of it. A stand-in that stayed silent would let that gap pass unnoticed
        // in every suite downstream.
        val backend = FakeMediaBackend()
        val seen: MutableList<String> = mutableListOf()
        backend.on(CanonicalBackendEvent.PLAY) { seen += "play" }
        backend.on(CanonicalBackendEvent.PLAYING) { seen += "playing" }

        backend.play()

        assertEquals(listOf("play", "playing"), seen)
    }

    @Test
    fun aRemovedListenerStopsHearing() {
        // Left attached, a fake accumulates a listener per test and the
        // hundredth run of a suite is measuring ninety-nine stale closures.
        val backend = FakeMediaBackend()
        var heard = 0
        val listener: (Any?) -> Unit = { heard += 1 }

        backend.on(CanonicalBackendEvent.PLAY, listener)
        backend.fire(CanonicalBackendEvent.PLAY)
        backend.off(CanonicalBackendEvent.PLAY, listener)
        backend.fire(CanonicalBackendEvent.PLAY)

        assertEquals(1, heard)
    }

    @Test
    fun itRecordsWhatItWasAskedToDoRatherThanOnlyThatItWasAsked() = runTest {
        val backend = FakeMediaBackend()

        backend.currentTime(42.0)
        backend.volume(0.25f)

        assertEquals(listOf(42.0), backend.seekedTo)
        assertEquals(listOf(0.25f), backend.volumesSet)
    }

    @Test
    fun theDefaultUrlGoesNowhereOnPurpose() {
        // .test is reserved and resolves nowhere, so a fake that accidentally
        // reached the network fails loudly instead of quietly downloading
        // somebody's homepage in a unit test.
        assertTrue(TestItem("a").url.contains(".test/"))
    }
}
