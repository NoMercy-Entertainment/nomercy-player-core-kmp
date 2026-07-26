// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The buffer heuristic, against memory figures rather than a device.
//
// These numbers were learned rather than chosen, so the tests record what was
// learned. The television ceiling is the one that cost something: a bedroom TV
// stopped responding, with the kernel thrashing at 45%, under a 500MB budget.
class BufferBudgetTest {

    private val heap = 256

    @Test
    fun aTelevisionGetsALowerCeilingThanAPhoneWithTheSameHeap() {
        // Not tuning. The budget a phone handles comfortably is the one the
        // television stopped responding under.
        val phone: BufferConfig = BufferBudget.forMemory(heap, isTv = false)
        val television: BufferConfig = BufferBudget.forMemory(heap, isTv = true)

        assertTrue(
            television.targetBufferBytes < phone.targetBufferBytes,
            "a television was given at least as much buffer as a phone with the same heap",
        )
    }

    @Test
    fun aTelevisionStartsFasterThanAPhone() {
        // Adaptive bitrate makes the first seconds the cheapest to be short on,
        // and a TV that takes five seconds to show a picture feels broken in a
        // way a phone does not.
        val phone: BufferConfig = BufferBudget.forMemory(heap, isTv = false)
        val television: BufferConfig = BufferBudget.forMemory(heap, isTv = true)

        assertTrue(television.bufferForPlaybackMs < phone.bufferForPlaybackMs)
    }

    @Test
    fun aLowRamDeviceIsCappedAtSixtyFourMegabytes() {
        // Below a gigabyte the subtitle layer peaks near 45MB and the codec
        // buffers want their own. Sixty-four is what is left.
        val config: BufferConfig = BufferBudget.forMemory(availableMb = 512, isLowRam = true)

        assertEquals(64 * BYTES_PER_MB, config.targetBufferBytes)
    }

    @Test
    fun aLowRamTelevisionIsTreatedAsLowRamRatherThanAsATelevision() {
        // The tighter constraint wins. A low-RAM television given the television
        // budget is the exact device that failed.
        val config: BufferConfig = BufferBudget.forMemory(availableMb = 512, isTv = true, isLowRam = true)

        assertEquals(64 * BYTES_PER_MB, config.targetBufferBytes)
    }

    @Test
    fun aGenerousDeviceStillHasACeiling() {
        // A third of a very large heap is more than any stream needs, and
        // holding it is memory taken from the rest of the app for nothing.
        assertEquals(350 * BYTES_PER_MB, BufferBudget.forMemory(availableMb = 4_096).targetBufferBytes)
    }

    @Test
    fun aTinyDeviceStillGetsAWorkableFloor() {
        // Below this there is not enough buffer to survive one hiccup, and a
        // player that stalls every few seconds is worse than one using slightly
        // more memory than it has to spare.
        assertEquals(80 * BYTES_PER_MB, BufferBudget.forMemory(availableMb = 64).targetBufferBytes)
    }

    @Test
    fun theRebufferWaitIsLongerThanTheInitialOne() {
        // After a stall the server is often coming back, and a short wait gives
        // up on it just before it does.
        val phone: BufferConfig = BufferBudget.forMemory(heap)

        assertTrue(phone.bufferForPlaybackAfterRebufferMs > phone.bufferForPlaybackMs)
    }

    @Test
    fun everyBufferWindowStaysInsideItsBounds() {
        // The scale factor multiplies four windows at once, and an unclamped one
        // produces a config the engine rejects at construction — which fails as
        // a player that will not start rather than one that buffers oddly.
        for (available in listOf(32, 64, 128, 256, 512, 1_024, 4_096)) {
            for (tv in listOf(false, true)) {
                val config: BufferConfig = BufferBudget.forMemory(available, isTv = tv)

                assertTrue(config.minBufferMs in 15_000..40_000, "minBufferMs out of range at $available tv=$tv")
                assertTrue(config.maxBufferMs in 50_000..180_000, "maxBufferMs out of range at $available tv=$tv")
                assertTrue(
                    config.maxBufferMs > config.minBufferMs,
                    "max is not above min at $available tv=$tv, which the engine refuses",
                )
            }
        }
    }

    @Test
    fun aTelevisionSaysItIsOne() {
        // The renderers factory reads this to decide about tunneling and
        // passthrough, so a config that lost the flag would silently take the
        // phone path on a television.
        assertTrue(BufferBudget.forMemory(heap, isTv = true).isTvDevice)
    }
}
