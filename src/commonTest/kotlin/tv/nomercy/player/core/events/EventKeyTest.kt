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

// Idempotency of dispose() is P03 Task 3's job (EventEmitter), not asserted here.
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
