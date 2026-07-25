// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// Runs the delay gates a before-listener registered and reports why the action
// should be refused, or null when every gate cleared.
//
// The Promise.allSettled semantics the web path composes with come from runGate
// turning a failure into a value, not from the scope: no child ever fails, so a
// slow gate is never cancelled by a fast failing sibling and a plugin cannot
// lose its cleanup to another plugin's error. A supervisorScope here would read
// as if it were carrying that guarantee while doing nothing, and no test could
// tell the difference — which is exactly how a defence stops being one.
internal suspend fun awaitDelayGates(gates: List<suspend () -> Unit>, timeoutMs: Long): String? {
    if (gates.isEmpty()) return null

    val results: List<Result<Unit>> = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            gates.map { gate -> async { runGate(gate) } }.awaitAll()
        }
    } ?: return PreventReason.DelayTimeout

    return if (results.any { it.isFailure }) PreventReason.DelayRejected else null
}

// runCatching would swallow the CancellationException the timeout above cancels
// gates with, leaving the coroutine looking successful while its scope is dying.
// Cancellation is rethrown; only a genuine gate failure becomes a Result.
@Suppress("TooGenericExceptionCaught")
private suspend fun runGate(gate: suspend () -> Unit): Result<Unit> =
    try {
        Result.success(gate())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
