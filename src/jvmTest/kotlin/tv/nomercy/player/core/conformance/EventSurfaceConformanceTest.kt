// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import tv.nomercy.player.conformance.ContractFixture
import tv.nomercy.player.core.events.CoreEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The base event map, both directions.
//
// The methods and the error codes had this and the events did not, which is the
// half a consumer meets first: a subscription is a string, the compiler has no
// opinion about it, and an event this port never declares is a handler that
// silently never runs. The Swift side gained the same check before the JVM had
// one at all.
class EventSurfaceConformanceTest {

    private val documented: Set<String> = ContractFixture.eventNames("base")

    private val declared: Set<String> = CoreEvents.all.map { it.name }.toSet()

    // Events the contract carries in the base map that core does not raise, each
    // with the reason it is somebody else's. Empty, and that is the finding: the
    // base map is fully declared here.
    //
    // It did not start empty. canplay, stalled and waiting were written down as
    // exemptions on the assumption they were base events core delegates — they
    // are video-map events and were never core's to raise, so excusing them
    // would have made this gate look three holes larger than it is. The test
    // below is what said so.
    private val raisedElsewhere: Map<String, String> = emptyMap()

    @Test
    fun coreDeclaresNoEventTheEcosystemHasNeverHeardOf() {
        // The direction that matters more. A consumer subscribing to an event no
        // other client emits has written code that will never run, and nothing
        // in any compiler will mention it.
        assertEquals(
            emptySet(),
            declared - documented,
            "core declares events the contract does not carry",
        )
    }

    @Test
    fun everyBaseEventCoreDoesNotRaiseHasAStatedReason() {
        assertEquals(
            raisedElsewhere.keys,
            documented - declared,
            "a base event is undeclared with no reason recorded, or a reason outlived its event",
        )
    }

    @Test
    fun theReasonListDoesNotOutliveTheEventsItExcuses() {
        // A reason for an event that no longer exists is a note nobody will ever
        // delete, and it makes the exemption list look smaller than it is.
        val unknown = raisedElsewhere.keys - documented

        assertEquals(emptySet(), unknown, "reasons recorded for events the contract no longer has")
    }

    @Test
    fun theBaseMapIsPopulatedRatherThanEmpty()  {
        // A comparison against an empty set passes forever, which is how a
        // filter that matched nothing would look exactly like a port that is
        // complete.
        assertTrue(documented.size > 50, "only ${documented.size} base events read from the contract")
    }
}
