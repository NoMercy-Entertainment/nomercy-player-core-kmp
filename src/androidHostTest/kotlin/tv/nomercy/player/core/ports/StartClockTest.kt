// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A cast remote starts the service again while Media3's notification from the
// first start is still posted. That second start has its own platform clock.
class StartClockTest {

    private class Rig(var media3: String? = null) {
        val events = mutableListOf<String>()
        val requested = AtomicBoolean(false)
        val clock = StartClock(
            media3Notification = { media3 },
            promote = { existing -> events += if (existing == null) PLACEHOLDER else PROMOTED_MEDIA3 },
            startRequested = requested,
        )

        fun stop() = clock.answerThenStop { events += STOP }
    }

    @Test
    fun stopBeforeOnStartCommandStillPromotesFirst() {
        val rig = Rig(media3 = MEDIA3)
        rig.clock.onStartCommand()
        rig.clock.answer()
        requestStart(rig.requested) {}

        rig.stop()

        assertEquals(listOf(PROMOTED_MEDIA3, PROMOTED_MEDIA3, STOP), rig.events)
        assertFalse(rig.requested.get())
    }

    @Test
    fun rejectedRequestLeavesNothingPending() {
        val rig = Rig()

        assertFailsWith<IllegalStateException> {
            requestStart(rig.requested) { error("ForegroundServiceStartNotAllowedException") }
        }
        rig.stop()

        assertFalse(rig.requested.get())
        assertEquals(listOf(STOP), rig.events)
    }

    @Test
    fun secondStartWithMedia3PostedPromotesBeforeStop() {
        val rig = Rig(media3 = MEDIA3)
        rig.clock.onStartCommand()
        rig.clock.answer()
        rig.clock.onStartCommand()

        rig.stop()

        assertEquals(listOf(PROMOTED_MEDIA3, PROMOTED_MEDIA3, STOP), rig.events)
    }

    @Test
    fun secondStartWithoutStopIsAnsweredByTheBackstop() {
        val rig = Rig(media3 = MEDIA3)
        rig.clock.onStartCommand()

        assertTrue(rig.clock.answer())

        assertEquals(listOf(PROMOTED_MEDIA3), rig.events)
        assertFalse(rig.clock.pending)
    }

    @Test
    fun noMedia3NotificationUsesThePlaceholder() {
        val rig = Rig()
        rig.clock.onStartCommand()

        rig.stop()

        assertEquals(listOf(PLACEHOLDER, STOP), rig.events)
    }

    @Test
    fun answeredClockIsNotPromotedAgain() {
        val rig = Rig()
        rig.clock.onStartCommand()
        rig.clock.answer()

        assertFalse(rig.clock.answer())
        rig.stop()

        assertEquals(listOf(PLACEHOLDER, STOP), rig.events)
    }

    @Test
    fun boundOnlyServiceHasNoClockToAnswer() {
        val rig = Rig()

        rig.stop()

        assertEquals(listOf(STOP), rig.events)
    }

    private companion object {
        const val MEDIA3 = "media3"
        const val PROMOTED_MEDIA3 = "startForeground(media3)"
        const val PLACEHOLDER = "startForeground(placeholder)"
        const val STOP = "stopSelf"
    }
}
