// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeforeEventTest {
    @Test
    fun aFreshEventIsNotPreventedNotStoppedAndNotDelayed() {
        val event = BeforeEvent(data = 1)

        assertFalse(event.isDefaultPrevented())
        assertFalse(event.isPropagationStopped())
        assertFalse(event.isDelayed())
    }

    @Test
    fun preventDefaultAndStopPropagationAreIndependentAndBothLatch() {
        val prevented = BeforeEvent(data = 1)
        prevented.preventDefault()
        assertTrue(prevented.isDefaultPrevented())
        assertFalse(prevented.isPropagationStopped())

        val stopped = BeforeEvent(data = 1)
        stopped.stopImmediatePropagation()
        assertTrue(stopped.isPropagationStopped())
        assertFalse(stopped.isDefaultPrevented())
    }

    @Test
    fun dataIsMutableSoAListenerCanReshapeThePayload() {
        val event = BeforeEvent(data = "before")

        event.data = "after"

        assertEquals("after", event.data)
    }

    @Test
    fun everyRegisteredDelayGateIsHandedBackInRegistrationOrder() {
        val event = BeforeEvent(data = 1)
        val ran = mutableListOf<Int>()
        event.delay { ran.add(1) }
        event.delay { ran.add(2) }

        assertTrue(event.isDelayed())
        val gates = event.consumeDelays()
        assertEquals(2, gates.size)
    }

    @Test
    fun consumingGatesCopiesThemSoRegisteringDuringIterationIsSafe() {
        val event = BeforeEvent(data = 1)
        event.delay { }
        val snapshot = event.consumeDelays()

        event.delay { }

        assertEquals(1, snapshot.size)
        assertEquals(2, event.consumeDelays().size)
    }

    @Test
    fun reasonConstantsAreStringIdenticalToTheWebPreventedReasonUnion() {
        assertEquals("listener-prevented", PreventReason.ListenerPrevented)
        assertEquals("delay-rejected", PreventReason.DelayRejected)
        assertEquals("delay-timeout", PreventReason.DelayTimeout)
    }
}
