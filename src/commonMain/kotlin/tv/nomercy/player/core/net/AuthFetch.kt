// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tv.nomercy.player.core.controllers.AuthController
import tv.nomercy.player.core.errors.AuthError
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.NetworkError
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Fetcher
import tv.nomercy.player.core.ports.RetryConfig
import tv.nomercy.player.core.ports.delayMs

// The request pipeline the web calls authFetch: sign, send, classify, retry.
//
// The transport port only moves bytes, so every caller that needed to know WHY
// a request failed was reading a status integer and deciding for itself. That
// is how two callers come to disagree about whether a 410 is worth retrying,
// and it is why thirteen `core:network/*` codes sat in the contract with
// nothing in this library able to raise one.
//
// The ladder below is the web's, branch for branch, because a consumer catching
// `core:network/gone` on one platform and `core:network/client-error` on the
// other has been handed two failures wearing one name.

private const val DEFAULT_TIMEOUT_MS = 30_000L

/** What happened to one attempt, before the loop decides what to do about it. */
private sealed interface Outcome {
    data class Done(val response: FetchResponse) : Outcome

    data class Fail(val error: NetworkError) : Outcome

    // [terminal] is what to raise if the budget runs out on this reason, so
    // exhausting three retries against a 503 reports the 503 rather than a
    // generic timeout the server never sent.
    data class Again(
        val reason: String,
        val delayMs: Long,
        val consumesAttempt: Boolean,
        val terminal: NetworkError? = null,
        val status: Int? = null,
    ) : Outcome
}

/** What a host forwards onto the event bus while a request is in flight. */
public sealed interface FetchSignal {
    public data class Start(val url: String) : FetchSignal

    public data class Retry(val url: String, val attempt: Int, val reason: String, val delayMs: Long) : FetchSignal

    public data class Complete(val url: String, val ok: Boolean, val status: Int?) : FetchSignal
}

// 4xx and 5xx, split exactly as the web splits them. A default arm rather than
// an exhaustive list, because a status nobody has a name for is still a client
// or a server failure and inventing a code per number would be worse.
public object HttpFailure {

    public fun codeForClientError(status: Int): String = when (status) {
        HttpStatus.NOT_FOUND -> CoreErrorCodes.NOT_FOUND
        HttpStatus.REQUEST_TIMEOUT -> CoreErrorCodes.REQUEST_TIMEOUT
        HttpStatus.GONE -> CoreErrorCodes.GONE
        HttpStatus.TOO_MANY_REQUESTS -> CoreErrorCodes.RATE_LIMITED
        else -> CoreErrorCodes.CLIENT_ERROR
    }

    public fun codeForServerError(status: Int): String = when (status) {
        HttpStatus.INTERNAL_SERVER_ERROR -> CoreErrorCodes.SERVER_ERROR
        HttpStatus.BAD_GATEWAY -> CoreErrorCodes.BAD_GATEWAY
        HttpStatus.SERVICE_UNAVAILABLE -> CoreErrorCodes.SERVICE_UNAVAILABLE
        HttpStatus.GATEWAY_TIMEOUT -> CoreErrorCodes.GATEWAY_TIMEOUT
        else -> CoreErrorCodes.SERVER_ERROR_OTHER
    }
}

public class AuthFetch(
    private val fetcher: Fetcher,
    private val auth: AuthController? = null,
    // One by default, as on the web: a token still rejected after a refresh is
    // not one another refresh will fix.
    private val maxRefreshes: Int = 1,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onSignal: (FetchSignal) -> Unit = {},
) {

    public suspend fun fetch(
        url: String,
        opts: FetchOptions = FetchOptions(),
        retry: RetryConfig = RetryConfig(attempts = 3),
    ): FetchResponse {
        onSignal(FetchSignal.Start(url))

        // At least one attempt. attempts = 0 is "do not RETRY", which is not
        // "do not send" — reading it as the latter turns every no-retry policy
        // into a request that never left.
        val ledger = RetryLedger(maxOf(1, retry.attempts))

        while (ledger.hasAttemptLeft()) {
            when (val outcome: Outcome = attemptOnce(url, opts, retry, ledger)) {
                is Outcome.Done -> {
                    onSignal(FetchSignal.Complete(url, ok = true, status = outcome.response.status))
                    return outcome.response
                }

                is Outcome.Fail -> {
                    onSignal(FetchSignal.Complete(url, ok = false, status = outcome.error.httpStatus()))
                    throw outcome.error
                }

                is Outcome.Again -> waitAndRecord(url, outcome, ledger)
            }
        }

        onSignal(FetchSignal.Complete(url, ok = false, status = ledger.lastStatus))
        throw ledger.lastError ?: netError(CoreErrorCodes.NETWORK_TIMEOUT, null, "fetch retry budget exhausted")
    }

    /**
     * The response body, handed to [parse]. A parser that throws is the web's
     * `core:network/parse-failed` — the request arrived and the answer was
     * unreadable, which is a different failure from never getting one and is
     * the only way a caller can tell a broken server from an absent one.
     */
    public suspend fun <T> fetchParsed(
        url: String,
        opts: FetchOptions = FetchOptions(),
        retry: RetryConfig = RetryConfig(attempts = 3),
        parse: (String) -> T,
    ): T {
        val response: FetchResponse = fetch(url, opts, retry)

        return try {
            parse(response.body)
        }
        catch (cause: CancellationException) {
            throw cause
        }
        // A parser can throw anything at all; that it threw IS the failure.
        catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
            throw netError(CoreErrorCodes.PARSE_FAILED, response.status, "response parser threw", cause)
        }
    }

    // What happens between one attempt and the next, and what the caller is
    // told when the budget runs out.
    private suspend fun waitAndRecord(url: String, outcome: Outcome.Again, ledger: RetryLedger) {
        ledger.record(
            status = outcome.status,
            error = outcome.terminal,
            refreshed = outcome.reason == UNAUTHENTICATED_RETRY,
            consumesAttempt = outcome.consumesAttempt,
        )
        onSignal(FetchSignal.Retry(url, ledger.attemptNumber(), outcome.reason, outcome.delayMs))
        delay(outcome.delayMs)
    }

    private suspend fun attemptOnce(
        url: String,
        opts: FetchOptions,
        retry: RetryConfig,
        ledger: RetryLedger,
    ): Outcome {
        val attempt: Int = ledger.attemptNumber()
        val signed: String = auth?.transformUrl(url) ?: url
        val request: FetchOptions = auth?.signRequest(signed, opts) ?: opts

        val response: FetchResponse = try {
            withTimeoutOrNull(timeoutMs) { fetcher.fetch(signed, request) }
                ?: return Outcome.Again(
                    reason = "timeout",
                    delayMs = retry.delayMs(attempt),
                    consumesAttempt = true,
                    terminal = netError(CoreErrorCodes.NETWORK_TIMEOUT, null, "fetch timed out after ${timeoutMs}ms"),
                )
        }
        // Cancellation is the caller withdrawing the request, not a failure of
        // it. Kotlin reserves that path, so it goes back up untouched.
        catch (cause: CancellationException) {
            throw cause
        }
        // A transport can throw anything; that it threw IS the network failure.
        catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
            return Outcome.Again(
                reason = "network",
                delayMs = retry.delayMs(attempt),
                consumesAttempt = true,
                terminal = netError(CoreErrorCodes.OFFLINE, null, "network error", cause),
            )
        }

        return classify(response, retry, attempt, ledger.refreshesUsed)
    }

    // One return per HTTP outcome. Collapsing five named cases into a single
    // expression would put the 401, the 403, the 4xx and the 5xx rules in one
    // condition, which is where a reader stops being able to check them.
    @Suppress("ReturnCount")
    private suspend fun classify(
        response: FetchResponse,
        retry: RetryConfig,
        attempt: Int,
        refreshesUsed: Int,
    ): Outcome {
        val status: Int = response.status

        if (status == HttpStatus.UNAUTHORIZED) return unauthenticated(status, refreshesUsed)

        if (status == HttpStatus.FORBIDDEN) {
            return Outcome.Fail(
                authError(
                    CoreErrorCodes.FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "forbidden (403) — authenticated but not authorized for this resource",
                ),
            )
        }

        if (status in HttpStatus.CLIENT_ERRORS) {
            return Outcome.Fail(netError(HttpFailure.codeForClientError(status), status, "HTTP $status"))
        }

        if (status >= HttpStatus.INTERNAL_SERVER_ERROR) {
            return Outcome.Again(
                reason = "http-5xx",
                delayMs = retry.delayMs(attempt),
                consumesAttempt = true,
                terminal = netError(HttpFailure.codeForServerError(status), status, "HTTP $status"),
                status = status,
            )
        }

        return Outcome.Done(response)
    }

    // Budget exhausted, refresh threw, refresh declined, refresh worked — four
    // outcomes a caller must be able to tell apart from the code.
    @Suppress("ReturnCount")
    private suspend fun unauthenticated(status: Int, refreshesUsed: Int): Outcome {
        if (refreshesUsed >= maxRefreshes) {
            return Outcome.Fail(authError(CoreErrorCodes.UNAUTHENTICATED, status, "authentication required (401)"))
        }

        val refreshed: Boolean = try {
            auth?.refresh() ?: false
        }
        catch (cause: CancellationException) {
            throw cause
        }
        // A transport can throw anything; that it threw IS the network failure.
        catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
            return Outcome.Fail(authError(CoreErrorCodes.REFRESH_FAILED, status, "token refresh failed", cause))
        }

        if (!refreshed) {
            return Outcome.Fail(authError(CoreErrorCodes.REFRESH_FAILED, status, "token refresh failed"))
        }

        // Free, as on the web: a refresh is not a failed attempt, and charging
        // one costs the caller a retry it never used.
        return Outcome.Again(UNAUTHENTICATED_RETRY, 0L, consumesAttempt = false, status = status)
    }

    private fun netError(code: String, status: Int?, message: String, cause: Throwable? = null): NetworkError =
        NetworkError(
            code = code,
            scope = ErrorScope.core(),
            severity = Severity.ERROR,
            message = message,
            cause = cause,
            context = statusContext(status),
        )

    private fun authError(code: String, status: Int?, message: String, cause: Throwable? = null): AuthError =
        AuthError(
            code = code,
            scope = ErrorScope.core(),
            severity = Severity.ERROR,
            message = message,
            cause = cause,
            context = statusContext(status),
        )

    private fun statusContext(status: Int?): Map<String, Any?> =
        if (status == null) emptyMap() else mapOf("httpStatus" to status)

    private companion object {
        const val UNAUTHENTICATED_RETRY = "unauthenticated"
    }
}

private fun NetworkError.httpStatus(): Int? = context["httpStatus"] as? Int
