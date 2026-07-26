// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A server that signs playback urls, which is what NoMercy's does.
private class TokenSigningAuth(
    private var token: String,
    private val refreshSucceeds: Boolean = true,
) : AuthController() {
    var refreshes: Int = 0
        private set

    override fun transformUrl(url: String): String = "$url?token=$token"

    override suspend fun refresh(): Boolean {
        refreshes += 1
        if (!refreshSucceeds) return false
        token = "renewed"
        return true
    }
}

class AuthAndEngineTest {

    private fun player(): ComposedPlayer = ComposedPlayer(backend = FakeMediaBackend())

    @Test
    fun anItemsUrlIsSignedOnTheWayToTheEngine() = runTest {
        // The url on a queue item is not the url that plays. Doing this in the
        // one loading path means a per-library loader that forgets about auth
        // does not exist.
        val player: ComposedPlayer = player()
        player.auth(TokenSigningAuth("first"))

        player.load(TestItem("a", url = "https://server.test/watch/a.m3u8"))

        assertEquals(
            listOf("https://server.test/watch/a.m3u8?token=first"),
            (player.backend() as FakeMediaBackend).loadedUrls,
        )
    }

    @Test
    fun aRefreshedTokenIsTheOneTheNextLoadUses() = runTest {
        val auth = TokenSigningAuth("expired")
        val player: ComposedPlayer = player()
        player.auth(auth)

        player.refreshAuth()
        player.load(TestItem("a", url = "https://server.test/a.m3u8"))

        assertEquals(1, auth.refreshes)
        assertEquals(
            listOf("https://server.test/a.m3u8?token=renewed"),
            (player.backend() as FakeMediaBackend).loadedUrls,
        )
    }

    @Test
    fun aSuccessfulRefreshIsAnnouncedSoCachedFetchersReResolve() = runTest {
        val player: ComposedPlayer = player()
        player.auth(TokenSigningAuth("expired"))
        var refreshedAt: Double? = null
        player.on(CoreEvents.AuthRefreshed) { refreshedAt = it.tokenAcquiredAt }

        assertTrue(player.refreshAuth())

        assertTrue(refreshedAt != null, "nothing announced the refresh")
    }

    @Test
    fun aFailedRefreshSaysSoRatherThanClaimingSuccess() = runTest {
        // A consumer needs to tell "signed in again" from "the session is gone
        // and the viewer has to do something", and they are the same call.
        val player: ComposedPlayer = player()
        player.auth(TokenSigningAuth("expired", refreshSucceeds = false))
        var failed = false
        player.on(CoreEvents.AuthFailed) { failed = true }

        assertFalse(player.refreshAuth())

        assertTrue(failed)
    }

    @Test
    fun aPlayerWithNoAuthRefreshesSuccessfully() = runTest {
        // There is no failure in having no token to renew, and making a
        // consumer whose media needs no authorisation implement a method to say
        // so would be a requirement invented by the library.
        val player: ComposedPlayer = player()
        var announcements = 0
        player.on(CoreEvents.AuthRefreshed) { announcements += 1 }

        assertTrue(player.refreshAuth())

        assertEquals(1, announcements)
    }

    @Test
    fun theAuthControllerCanBeReadBackButCarriesNoToken() {
        // Read back so a consumer can tell whether one is installed. There is no
        // getter for the token itself, on purpose: a bearer reachable from the
        // player is reachable from every plugin the player has.
        val player: ComposedPlayer = player()
        assertNull(player.auth())

        val installed = TokenSigningAuth("secret")
        player.auth(installed)

        assertEquals(installed, player.auth())
    }

    @Test
    fun anItemWithNoAuthLoadsItsUrlUnchanged() = runTest {
        val player: ComposedPlayer = player()

        player.load(TestItem("a", url = "https://server.test/open.mp4"))

        assertEquals(listOf("https://server.test/open.mp4"), (player.backend() as FakeMediaBackend).loadedUrls)
    }

    @Test
    fun aPlayerWithNoEngineHasNoBackendRatherThanAStandIn() {
        assertNull(ComposedPlayer().backend())
    }
}
