// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.net

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.AuthController
import tv.nomercy.player.core.errors.AuthError
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.NetworkError
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Fetcher
import tv.nomercy.player.core.ports.RetryConfig
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A transport that answers with the statuses it was handed, in order, and
// records what it was asked.
private class ScriptedFetcher(private vararg val statuses: Int, private val body: String = "ok") : Fetcher {
    var calls: Int = 0
        private set

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        val status: Int = statuses.getOrElse(calls) { statuses.last() }
        calls += 1
        return FetchResponse(status = status, body = body)
    }
}

private class ThrowingFetcher(private val failures: Int) : Fetcher {
    var calls: Int = 0
        private set

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        calls += 1
        if (calls <= failures) throw IllegalStateException("no route to host")
        return FetchResponse(status = 200, body = "ok")
    }
}

// An auth that can be told whether its refresh works.
private class ScriptedAuth(private val refreshes: Boolean, private val throws: Boolean = false) : AuthController() {
    var refreshCalls: Int = 0
        private set

    override suspend fun refresh(): Boolean {
        refreshCalls += 1
        if (throws) throw IllegalStateException("refresh endpoint is down")
        return refreshes
    }
}

private val NO_WAIT = RetryConfig(attempts = 3, baseMs = 0)

// The HTTP ladder, which is the whole reason thirteen network codes are in the
// contract. A status the caller has to classify itself is a status two callers
// will classify differently.
class AuthFetchTest {

    private fun pipeline(fetcher: Fetcher, auth: AuthController? = null, maxRefreshes: Int = 1): AuthFetch =
        AuthFetch(fetcher = fetcher, auth = auth, maxRefreshes = maxRefreshes)

    @Test
    fun aSuccessfulRequestComesBackWithItsBody() = runTest {
        val response = pipeline(ScriptedFetcher(200)).fetch("https://example.test/a")

        assertEquals(200, response.status)
        assertEquals("ok", response.body)
    }

    @Test
    fun eachNamedClientFailureRaisesItsOwnCode() = runTest {
        val expected: Map<Int, String> = mapOf(
            404 to CoreErrorCodes.NOT_FOUND,
            408 to CoreErrorCodes.REQUEST_TIMEOUT,
            410 to CoreErrorCodes.GONE,
            429 to CoreErrorCodes.RATE_LIMITED,
            418 to CoreErrorCodes.CLIENT_ERROR,
        )

        for ((status, code) in expected) {
            val raised = assertFailsWith<NetworkError> {
                pipeline(ScriptedFetcher(status)).fetch("https://example.test/a", retry = NO_WAIT)
            }
            assertEquals(code, raised.code, "HTTP $status")
        }
    }

    @Test
    fun aClientFailureIsNotRetried() = runTest {
        // Asking again for something that is gone is the same answer and a
        // delay the viewer pays for.
        val fetcher = ScriptedFetcher(410)

        assertFailsWith<NetworkError> { pipeline(fetcher).fetch("https://example.test/a", retry = NO_WAIT) }

        assertEquals(1, fetcher.calls)
    }

    @Test
    fun eachNamedServerFailureRaisesItsOwnCodeOnceTheBudgetIsGone() = runTest {
        val expected: Map<Int, String> = mapOf(
            500 to CoreErrorCodes.SERVER_ERROR,
            502 to CoreErrorCodes.BAD_GATEWAY,
            503 to CoreErrorCodes.SERVICE_UNAVAILABLE,
            504 to CoreErrorCodes.GATEWAY_TIMEOUT,
            507 to CoreErrorCodes.SERVER_ERROR_OTHER,
        )

        for ((status, code) in expected) {
            val raised = assertFailsWith<NetworkError> {
                pipeline(ScriptedFetcher(status)).fetch("https://example.test/a", retry = NO_WAIT)
            }
            assertEquals(code, raised.code, "HTTP $status")
        }
    }

    @Test
    fun aServerFailureIsRetriedAndASucceedingRetryIsTheAnswer() = runTest {
        val fetcher = ScriptedFetcher(503, 503, 200)

        val response = pipeline(fetcher).fetch("https://example.test/a", retry = NO_WAIT)

        assertEquals(200, response.status)
        assertEquals(3, fetcher.calls)
    }

    @Test
    fun aTransportThatThrowsIsOfflineAndIsRetried() = runTest {
        val fetcher = ThrowingFetcher(failures = 1)

        val response = pipeline(fetcher).fetch("https://example.test/a", retry = NO_WAIT)

        assertEquals(200, response.status)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun aTransportThatKeepsThrowingReportsOffline() = runTest {
        val raised = assertFailsWith<NetworkError> {
            pipeline(ThrowingFetcher(failures = 99)).fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(CoreErrorCodes.OFFLINE, raised.code)
    }

    @Test
    fun aRefusalIsNeverRefreshedAndNeverRetried() = runTest {
        val auth = ScriptedAuth(refreshes = true)
        val fetcher = ScriptedFetcher(403)

        val raised = assertFailsWith<AuthError> {
            pipeline(fetcher, auth).fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(CoreErrorCodes.FORBIDDEN, raised.code)
        assertEquals(0, auth.refreshCalls)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun anExpiredTokenIsRefreshedOnceAndTheRetryIsFree() = runTest {
        // Free is the point: charging the refresh an attempt costs the caller a
        // retry it never used, and a one-attempt policy would then never send
        // the request that the fresh token was for.
        val auth = ScriptedAuth(refreshes = true)
        val fetcher = ScriptedFetcher(401, 200)

        val response = pipeline(fetcher, auth).fetch("https://example.test/a", retry = RetryConfig(attempts = 1))

        assertEquals(200, response.status)
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun aSecondRefusalAfterARefreshIsUnauthenticated() = runTest {
        val auth = ScriptedAuth(refreshes = true)

        val raised = assertFailsWith<AuthError> {
            pipeline(ScriptedFetcher(401), auth).fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(CoreErrorCodes.UNAUTHENTICATED, raised.code)
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun aRefreshThatFailsIsItsOwnFailure() = runTest {
        // "Sign in" and "the sign-in you have is broken" need different answers
        // from a consumer, so they are different codes.
        val raised = assertFailsWith<AuthError> {
            pipeline(ScriptedFetcher(401), ScriptedAuth(refreshes = false))
                .fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(CoreErrorCodes.REFRESH_FAILED, raised.code)
    }

    @Test
    fun aRefreshThatThrowsIsTheSameFailure() = runTest {
        val raised = assertFailsWith<AuthError> {
            pipeline(ScriptedFetcher(401), ScriptedAuth(refreshes = true, throws = true))
                .fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(CoreErrorCodes.REFRESH_FAILED, raised.code)
    }

    @Test
    fun anUnreadableAnswerIsNotAnAbsentOne() = runTest {
        val raised = assertFailsWith<NetworkError> {
            pipeline(ScriptedFetcher(200)).fetchParsed("https://example.test/a", retry = NO_WAIT) {
                throw IllegalArgumentException("not the JSON anybody expected")
            }
        }

        assertEquals(CoreErrorCodes.PARSE_FAILED, raised.code)
    }

    @Test
    fun aParsedResponseComesBackParsed() = runTest {
        val length: Int = pipeline(ScriptedFetcher(200)).fetchParsed("https://example.test/a") { it.length }

        assertEquals(2, length)
    }

    @Test
    fun theStatusTravelsWithTheFailureSoNobodyParsesTheSentence() = runTest {
        val raised = assertFailsWith<NetworkError> {
            pipeline(ScriptedFetcher(404)).fetch("https://example.test/a", retry = NO_WAIT)
        }

        assertEquals(404, raised.context["httpStatus"])
    }

    @Test
    fun aRequestAnnouncesItsStartRetriesAndOutcome() = runTest {
        // Three events the contract declares and nothing in this library ever
        // emitted, which is a consumer's whole view of a request in flight.
        val seen: MutableList<FetchSignal> = mutableListOf()
        val pipeline = AuthFetch(fetcher = ScriptedFetcher(503, 200), onSignal = { seen += it })

        pipeline.fetch("https://example.test/a", retry = NO_WAIT)

        assertTrue(seen.first() is FetchSignal.Start, "no start")
        assertTrue(seen.any { it is FetchSignal.Retry }, "no retry")
        assertEquals(FetchSignal.Complete("https://example.test/a", ok = true, status = 200), seen.last())
    }

    @Test
    fun aPlayerFetchAnnouncesItselfOnTheBus() = runTest {
        // The three fetch:* keys were declared, had payload classes, and were
        // fired by nothing — a consumer subscribing to them heard silence for
        // every request the player ever made.
        val player = ComposedPlayer(backend = FakeMediaBackend(), fetcher = ScriptedFetcher(503, 200))
        val seen: MutableList<String> = mutableListOf()
        player.onAll { name, _ -> if (name.startsWith("fetch:")) seen += name }

        player.fetch("https://example.test/a", FetchOptions())

        assertEquals(listOf("fetch:start", "fetch:retry", "fetch:complete"), seen)
    }
}
