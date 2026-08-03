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
import kotlin.test.assertTrue

// The registry every listener resolves through.
//
// An emitter keys on the wire name, so two events sharing one is not a tidiness
// problem: a consumer subscribing to the second is handed the first's payload,
// or never hears at all. Nothing checked, and 176 names were maintained by hand.
class EventRegistryTest {

    @Test
    fun noTwoEventsShareAWireName() {
        val names: List<String> = CoreEvents.all.map { it.name }
        val duplicated: List<String> = names.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

        assertEquals(emptyList(), duplicated, "a listener on one of these is handed the other's payload")
    }

    @Test
    fun andNoneOfThemIsNameless() {
        // A blank name subscribes to nothing and emits to nothing, silently.
        assertTrue(CoreEvents.all.all { it.name.isNotBlank() }, "an event has no wire name")
    }

    @Test
    fun theRegistryIsNotEmpty() {
        // The list is hand-maintained. An `all` that quietly became empty would
        // make both assertions above pass while proving nothing whatsoever.
        assertTrue(CoreEvents.all.size >= REGISTERED, "the registry shrank to ${CoreEvents.all.size}")
    }
}

// What CORE held when this was written - not the trio-wide count, which spans
// the video and music registries too. A floor, so an event removed on purpose
// is a deliberate edit here rather than a silent loss.
private const val REGISTERED = 152
