// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cast

import tv.nomercy.player.core.ports.UnsupportedExternalPlayback
import tv.nomercy.player.core.ports.fakes.FakeExternalPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Asking the platform to move playback somewhere else.
class AirPlayHandoffTest {

    @Test
    fun onAPlatformThatCanRouteItAsksTheSystemToShowItsPicker() {
        val external = FakeExternalPlayback()

        val result: HandoffResult = AirPlayHandoff(external).transfer()

        assertEquals(HandoffResult.Routed, result)
        assertEquals(1, external.pickerShown)
    }

    @Test
    fun onAPlatformThatCannotItSaysWhyRatherThanFailingQuietly() {
        // A chrome that only knows it failed can say nothing useful, and this is
        // the difference between a button that should never have been drawn and
        // one that hit a real problem.
        val result: HandoffResult = AirPlayHandoff(UnsupportedExternalPlayback).transfer()

        val unsupported = assertIs<HandoffResult.Unsupported>(result)
        assertTrue(unsupported.reason.isNotEmpty())
    }

    @Test
    fun aPlatformWithoutARouteSenderIsNeverAskedToShowAPicker() {
        // The other half. Asking is a no-op today, and a version that asked
        // anyway would depend on that staying true forever on every platform.
        val external = FakeExternalPlayback(isSupported = false)

        AirPlayHandoff(external).transfer()

        assertEquals(0, external.pickerShown, "a platform with nothing to show was asked to show it")
    }

    @Test
    fun handingOverDoesNotTouchTheLocalPlaybackAtAll() {
        // The whole reason this is not a CastSender. That seam pauses this
        // device before handing the item over; here the system moves the output
        // of the player still running, so a pause would stop the very playback
        // about to be routed.
        val external = FakeExternalPlayback()

        AirPlayHandoff(external).transfer()

        assertEquals(0, external.disposals)
        assertTrue(external.allowed, "handing over withdrew its own permission")
    }
}
