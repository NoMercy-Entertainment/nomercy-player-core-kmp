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

/**
 * Idempotency is deliberately not asserted here. A `fun interface` runs
 * whatever body it was given, so guarding a second [Subscription.dispose]
 * call is the responsibility of whoever constructs one — `EventEmitter`, and
 * only there, per P03 Task 3.
 */
class EventKeyTest {

    @Test
    fun eventKeyCarriesTheWebStringName() {
        val key = EventKey<Int>("stream:error")
        assertEquals("stream:error", key.name)
    }

    @Test
    fun disposeRunsTheBodyItWasConstructedWith() {
        var removals = 0
        val sub = Subscription { removals++ }

        sub.dispose()

        assertEquals(1, removals)
    }
}
