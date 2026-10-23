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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A car that opens NoMercy cold binds the service before any player exists.
// What it is answered with decides whether it ever asks for the catalog.
class BrowseSessionCoordinatorTest {

    private class Session(val label: String) {
        var released = false
    }

    // Stands in for Media3's registry: one live session at a time, and a throw
    // on a second add, the way a duplicate session id answers.
    // [shared] stands in for the one long-lived session the host hands both the
    // service and the transport; without it every build is a new session.
    private class Registry(shared: Session? = null) {
        val registered = mutableListOf<Session>()
        var maxRegistered = 0
        var duplicateAttempts = 0
        var built = 0
        var buildCalls = 0
        var removals = 0

        val coordinator = BrowseSessionCoordinator(
            build = {
                buildCalls++
                shared ?: Session("browse-${++built}")
            },
            add = { session ->
                if (registered.any { it === session }) {
                    duplicateAttempts++
                    error("Session ID must be unique")
                }
                registered += session
                maxRegistered = maxOf(maxRegistered, registered.size)
            },
            remove = { session ->
                removals++
                registered.removeAll { it === session }
            },
            release = { session -> session.released = true },
        )
    }

    @Test
    fun aBrowserBindingWithNoPlaybackIsAnsweredWithABrowseSession() {
        val registry = Registry()
        registry.coordinator.onBind()

        val answered = registry.coordinator.sessionForBrowser()

        assertNotNull(answered)
        assertEquals(listOf(answered), registry.registered)
    }

    @Test
    fun askingAgainReturnsTheSameBrowseSessionRatherThanBuildingAnother() {
        val registry = Registry()
        registry.coordinator.onBind()

        val first = registry.coordinator.sessionForBrowser()
        val second = registry.coordinator.sessionForBrowser()

        assertSame(first, second)
        assertEquals(1, registry.built)
    }

    @Test
    fun theTransportPublishingTakesOverAndOnlyItIsRegistered() {
        val registry = Registry()
        registry.coordinator.onBind()
        val browse = registry.coordinator.sessionForBrowser()
        val transport = Session("transport")

        val stop = registry.coordinator.onPublished(transport)

        assertFalse(stop)
        assertEquals(listOf(transport), registry.registered)
        assertTrue(browse!!.released)
        assertSame(transport, registry.coordinator.sessionForBrowser())
        assertEquals(1, registry.maxRegistered)
    }

    @Test
    fun theTransportReleasingWithABrowserBoundBringsTheBrowseSessionBack() {
        val registry = Registry()
        registry.coordinator.onBind()
        registry.coordinator.sessionForBrowser()
        val transport = Session("transport")
        registry.coordinator.onPublished(transport)

        val stop = registry.coordinator.onPublished(null)

        assertFalse(stop)
        val answered = registry.coordinator.sessionForBrowser()
        assertNotNull(answered)
        assertEquals(listOf(answered), registry.registered)
        assertEquals(1, registry.maxRegistered)
    }

    @Test
    fun noBrowserAndNoTransportMeansTheServiceStops() {
        val registry = Registry()
        val transport = Session("transport")
        registry.coordinator.onPublished(transport)

        assertTrue(registry.coordinator.onPublished(null))
    }

    // The service's collector starts with the flow's current null, before any
    // player exists. Stopping on it would end a service a car just bound to.
    @Test
    fun theNullThatStartsTheCollectorIsNotAStop() {
        val registry = Registry()

        assertFalse(registry.coordinator.onPublished(null))
    }

    @Test
    fun theLastBrowserLeavingWithNoTransportReleasesTheBrowseSessionAndStops() {
        val registry = Registry()
        registry.coordinator.onBind()
        val browse = registry.coordinator.sessionForBrowser()

        val stop = registry.coordinator.onUnbind()

        assertTrue(stop)
        assertTrue(browse!!.released)
        assertTrue(registry.registered.isEmpty())
        assertNull(registry.coordinator.current)
    }

    @Test
    fun aBrowserLeavingWhilePlaybackRunsLeavesPlaybackAlone() {
        val registry = Registry()
        registry.coordinator.onBind()
        val transport = Session("transport")
        registry.coordinator.onPublished(transport)

        val stop = registry.coordinator.onUnbind()

        assertFalse(stop)
        assertEquals(listOf(transport), registry.registered)
    }

    @Test
    fun oneOfTwoBrowsersLeavingKeepsTheBrowseSession() {
        val registry = Registry()
        registry.coordinator.onBind()
        registry.coordinator.onBind()
        val browse = registry.coordinator.sessionForBrowser()

        val stop = registry.coordinator.onUnbind()

        assertFalse(stop)
        assertSame(browse, registry.coordinator.sessionForBrowser())
    }

    @Test
    fun aSessionIsNeverRegisteredTwiceAndNeverTwoAtOnceAcrossAFullCycle() {
        val registry = Registry()
        registry.coordinator.onBind()
        registry.coordinator.sessionForBrowser()
        registry.coordinator.onPublished(Session("first"))
        registry.coordinator.onPublished(null)
        registry.coordinator.sessionForBrowser()
        registry.coordinator.onPublished(Session("second"))
        registry.coordinator.onPublished(Session("third"))

        assertEquals(0, registry.duplicateAttempts)
        assertEquals(1, registry.maxRegistered)
    }

    @Test
    fun aBrowseSessionThatCannotBeBuiltAnswersNullInsteadOfThrowing() {
        val coordinator = BrowseSessionCoordinator<Session>(
            build = { error("builder refused") },
            add = {},
            remove = {},
            release = {},
        )
        coordinator.onBind()

        assertNull(coordinator.sessionForBrowser())
    }

    // The car's browser holds the session it was bound to. Destroying it is what
    // Android Auto reports as onSessionDestroyed and answers with "can't load".
    @Test
    fun playbackStartingNeverDestroysTheSessionABoundBrowserHolds() {
        val shared = Session("shared")
        val registry = Registry(shared)
        registry.coordinator.onBind()
        val held = registry.coordinator.sessionForBrowser()

        val stop = registry.coordinator.onPublished(shared)

        assertFalse(stop)
        assertSame(shared, held)
        assertFalse(shared.released)
        assertEquals(0, registry.removals)
        assertEquals(listOf(shared), registry.registered)
    }

    @Test
    fun playbackEndingWithABrowserBoundKeepsTheSessionRegisteredAndAlive() {
        val shared = Session("shared")
        val registry = Registry(shared)
        registry.coordinator.onBind()
        registry.coordinator.sessionForBrowser()
        registry.coordinator.onPublished(shared)

        val stop = registry.coordinator.onPublished(null)

        assertFalse(stop)
        assertFalse(shared.released)
        assertEquals(0, registry.removals)
        assertEquals(listOf(shared), registry.registered)
        assertSame(shared, registry.coordinator.sessionForBrowser())
        assertNull(registry.coordinator.transport)
    }

    @Test
    fun theSameSessionServesBrowseThenPlaybackThenBrowseWithOneRegistration() {
        val shared = Session("shared")
        val registry = Registry(shared)
        registry.coordinator.onBind()
        registry.coordinator.sessionForBrowser()
        registry.coordinator.onPublished(shared)
        registry.coordinator.onPublished(null)
        registry.coordinator.onPublished(shared)
        registry.coordinator.onPublished(null)

        assertEquals(0, registry.duplicateAttempts)
        assertEquals(1, registry.maxRegistered)
        assertEquals(0, registry.removals)
        assertFalse(shared.released)
    }

    @Test
    fun aCarConnectingWhilePlaybackAlreadyRunsAttachesToThePlayingSession() {
        val shared = Session("shared")
        val registry = Registry(shared)
        registry.coordinator.onPublished(shared)
        registry.coordinator.onBind()

        assertSame(shared, registry.coordinator.sessionForBrowser())
        assertEquals(0, registry.buildCalls)
        assertEquals(listOf(shared), registry.registered)
    }

    @Test
    fun playbackEndingWithNoBrowserRemovesTheSessionAndStopsTheService() {
        val shared = Session("shared")
        val registry = Registry(shared)
        registry.coordinator.onPublished(shared)

        val stop = registry.coordinator.onPublished(null)

        assertTrue(stop)
        assertTrue(registry.registered.isEmpty())
    }

    @Test
    fun shuttingDownReleasesTheBrowseSession() {
        val registry = Registry()
        registry.coordinator.onBind()
        val browse = registry.coordinator.sessionForBrowser()

        registry.coordinator.shutdown()

        assertTrue(browse!!.released)
        assertTrue(registry.registered.isEmpty())
    }
}
