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

// 2020-01-01T00:00:00Z. Anything below this is a monotonic counter or a
// millisecond/second mix-up, not a wall clock.
private const val YEAR_2020_MILLIS = 1_577_836_800_000L

class ClockIdTest {

    @Test
    fun theDefaultClockIsAWallClockInMillisecondsNotAMonotonicCounter() {
        assertTrue(defaultClock().now() > YEAR_2020_MILLIS)
    }

    @Test
    fun theDefaultClockMovesForward() {
        val clock = defaultClock()
        val first = clock.now()

        var later = clock.now()
        while (later == first) later = clock.now()

        assertTrue(later > first)
    }

    @Test
    fun generatedIdsAreNonBlankAndDistinct() {
        val generator = defaultIdGenerator()

        val ids = List(1_000) { generator.next() }

        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun twoGeneratorsDoNotCollideWithEachOther() {
        // Two players in one process must not mint the same id.
        val first = List(500) { defaultIdGenerator().next() }
        val second = List(500) { defaultIdGenerator().next() }

        assertEquals(1_000, (first + second).toSet().size)
    }
}
