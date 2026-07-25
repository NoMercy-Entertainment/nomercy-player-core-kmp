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

class EventKeyTest {
    @Test
    fun eventKeyCarriesTheWebStringName() {
        val key = EventKey<Int>("stream:error")
        assertEquals("stream:error", key.name)
    }

    @Test
    fun unsubscribeRunsTheRemoverExactlyOnce() {
        var removals = 0
        val sub = Subscription { removals++ }

        assertTrue(sub.isActive)
        sub.unsubscribe()
        assertFalse(sub.isActive)
        sub.unsubscribe() // idempotent — must not run the remover again
        assertEquals(1, removals)
    }
}
