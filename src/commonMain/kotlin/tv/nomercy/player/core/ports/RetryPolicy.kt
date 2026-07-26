// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.errors.CoreErrorCodes

// Doubling past this would overflow a Long and wrap to a negative delay. Thirty
// doublings of a one-second base is already twelve days.
private const val MAX_DOUBLINGS = 30

public enum class Backoff(public val wire: String) {
    LINEAR("linear"),
    EXPONENTIAL("exponential"),
}

// What to do when an operation fails with a given error code.
//
// attempts = 0 is the canonical "do not retry", and it is the right answer more
// often than it looks: retrying a 403 or an unsupported codec just makes the
// same request again and delays telling the viewer.
public data class RetryConfig(
    val attempts: Int,
    val backoff: Backoff? = null,
    val baseMs: Long? = null,
    val maxMs: Long? = null,
    // Refresh the token before retrying. Only useful for authentication codes,
    // where the retry would otherwise fail identically.
    val refreshFirst: Boolean = false,
)

// Error code to behaviour. Keys can be an exact code, a "namespace:category/"
// prefix, an HTTP range, or "*".
public typealias RetryPolicy = Map<String, RetryConfig>

// Delay before the attempt-th retry, counting from 1. Zero when the config has
// no backoff, which means retry immediately.
public fun RetryConfig.delayMs(attempt: Int): Long {
    if (attempt < 1) return 0L
    val base: Long = baseMs ?: return 0L
    val doublings: Int = (attempt - 1).coerceAtMost(MAX_DOUBLINGS)
    val raw: Long = when (backoff) {
        Backoff.EXPONENTIAL -> base shl doublings
        Backoff.LINEAR -> base * attempt
        null -> 0L
    }
    return raw.coerceAtMost(maxMs ?: raw)
}

// Most specific wins: the exact code, then its category, then the HTTP range,
// then the catch-all. Falling through all four means do not retry, because a
// policy that has not been asked about a failure has not approved retrying it.
public fun RetryPolicy.resolve(code: String): RetryConfig =
    this[code]
        ?: categoryKey(code)?.let { this[it] }
        ?: rangeKey(code)?.let { this[it] }
        ?: this["*"]
        ?: RetryConfig(attempts = 0)

// "core:auth/forbidden" -> "core:auth/", so one entry covers a whole category.
private fun categoryKey(code: String): String? {
    val slash: Int = code.indexOf('/')
    return if (slash >= 0) code.substring(0, slash + 1) else null
}

private fun rangeKey(code: String): String? = when {
    code.startsWith("4") -> "4xx"
    code.startsWith("5") -> "5xx"
    else -> null
}

// The built-in table, mirroring the web policy entry for entry.
//
// The shape of it is the point: transport failures retry with backoff, and
// anything that will fail the same way twice does not. An expired token is the
// one case where retrying helps only after doing something first.
public val DEFAULT_RETRY_POLICY: RetryPolicy = mapOf(
    CoreErrorCodes.NETWORK_TIMEOUT to
        RetryConfig(attempts = 5, backoff = Backoff.EXPONENTIAL, baseMs = 500, maxMs = 30_000),
    CoreErrorCodes.SERVER_ERROR to
        RetryConfig(attempts = 3, backoff = Backoff.EXPONENTIAL, baseMs = 500, maxMs = 10_000),
    CoreErrorCodes.FRAGMENT_FAILED to
        RetryConfig(attempts = 5, backoff = Backoff.LINEAR, baseMs = 200, maxMs = 5_000),
    CoreErrorCodes.UNAUTHENTICATED to RetryConfig(attempts = 1, refreshFirst = true),
    CoreErrorCodes.FORBIDDEN to RetryConfig(attempts = 0),
    CoreErrorCodes.CODEC_UNSUPPORTED to RetryConfig(attempts = 0),
    "media/aborted" to RetryConfig(attempts = 0),
    "media/network" to
        RetryConfig(attempts = 3, backoff = Backoff.EXPONENTIAL, baseMs = 1_000, maxMs = 8_000),
    "media/decode-fatal-variant" to RetryConfig(attempts = 0),
    "media/decode-fatal-all" to RetryConfig(attempts = 0),
    CoreErrorCodes.QUEUE_EMPTY to RetryConfig(attempts = 0),
    "4xx" to RetryConfig(attempts = 0),
    "5xx" to RetryConfig(attempts = 3, backoff = Backoff.EXPONENTIAL, baseMs = 500, maxMs = 10_000),
    "*" to RetryConfig(attempts = 0),
)
