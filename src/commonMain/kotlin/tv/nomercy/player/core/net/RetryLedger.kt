// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.net

import tv.nomercy.player.core.errors.NetworkError

/**
 * How far through its retry budget one request is, and what went wrong so far.
 *
 * Four `var`s threaded through the retry loop by hand before this, which is
 * where the two rules that are easy to get wrong lived as loose statements:
 *
 *   - a refresh does NOT consume an attempt, or one expired token would spend
 *     the whole budget re-authenticating and the request would never be sent
 *   - the LAST error is what a caller is told when the budget runs out, not the
 *     first, because the first is usually a timeout and the last is the reason
 */
internal class RetryLedger(private val maxAttempts: Int) {

    private var attemptsUsed: Int = 0

    /** Refreshes are counted apart from attempts, and the reason is above. */
    var refreshesUsed: Int = 0
        private set

    var lastStatus: Int? = null
        private set

    var lastError: NetworkError? = null
        private set

    fun hasAttemptLeft(): Boolean = attemptsUsed < maxAttempts

    /** One-based, which is what a retry signal and a backoff curve both want. */
    fun attemptNumber(): Int = attemptsUsed + 1

    /**
     * What one failed attempt leaves behind.
     *
     * The pieces rather than the outcome itself, because the outcome type is
     * private to the file that produces it and widening it so a ledger could
     * name it would put the retry machinery on the kit's surface.
     */
    fun record(status: Int?, error: NetworkError?, refreshed: Boolean, consumesAttempt: Boolean) {
        status?.let { value -> lastStatus = value }
        error?.let { value -> lastError = value }
        if (refreshed) refreshesUsed += 1
        if (consumesAttempt) attemptsUsed += 1
    }
}
