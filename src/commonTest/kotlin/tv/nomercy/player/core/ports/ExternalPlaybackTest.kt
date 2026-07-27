// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.ports.fakes.FakeExternalPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A platform with nowhere to send playback, and one with somewhere.
//
// The first is most of them, and it has to be an ordinary answer rather than an
// error: a desktop with no AirPlay is not a desktop with a problem.
class ExternalPlaybackTest {

    @Test
    fun aPlatformWithNoRouteSenderSaysSoRatherThanFailing() {
        val external: ExternalPlayback = UnsupportedExternalPlayback

        assertFalse(external.isSupported)
        assertEquals(ExternalPlaybackState.Unsupported, external.state.value)
    }

    @Test
    fun everyIntentIsSafeWhereThereIsNothingToSendTo() {
        // Callers hold this port unconditionally, so the calls happen on every
        // platform whether or not anything can answer them. A throw here is a
        // crash on the platforms that were never going to support it.
        val external: ExternalPlayback = UnsupportedExternalPlayback

        external.setAllowed(true)
        external.showRoutePicker()
        external.dispose()
        external.dispose()

        assertEquals(ExternalPlaybackState.Unsupported, external.state.value)
    }

    @Test
    fun aSupportedPlatformStartsWithNothingPlayingElsewhere() {
        // Supported does not mean in use. A device that reported a route active
        // before one was chosen would have chrome drawing "playing on Kitchen"
        // over an empty room.
        val external = FakeExternalPlayback()

        assertTrue(external.isSupported)
        assertEquals(ExternalPlaybackState.Available(active = false, activeRoute = null), external.state.value)
    }

    @Test
    fun choosingARouteNamesItSoChromeCanSayWhereTheSoundWent() {
        val external = FakeExternalPlayback()

        external.routeChanged(ExternalRoute(id = "kitchen", name = "Kitchen", kind = RouteKind.AIRPLAY))

        val state = external.state.value as ExternalPlaybackState.Available
        assertTrue(state.active)
        assertEquals("Kitchen", state.activeRoute?.name)
    }

    @Test
    fun leavingARouteClearsTheNameAsWellAsTheFlag() {
        // A stale route name is worse than none. Someone walking out of range
        // sees the sound come back to their phone while the screen still says it
        // is playing in the kitchen.
        val external = FakeExternalPlayback()
        external.routeChanged(ExternalRoute(id = "kitchen", name = "Kitchen", kind = RouteKind.AIRPLAY))

        external.routeChanged(null)

        val state = external.state.value as ExternalPlaybackState.Available
        assertFalse(state.active)
        assertNull(state.activeRoute)
    }
}
