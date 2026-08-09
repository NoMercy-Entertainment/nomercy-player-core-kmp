// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * A stream that cannot be opened says so, rather than loading forever.
 *
 * This is the shape the desktop failed in on a real machine: one fixture was
 * served from a LAN host that was not running, and the player showed a spinner
 * with no error and no timeout — indistinguishable from a slow film, so nobody
 * could tell whether to wait. The backend polls properties for state, and a
 * failed open leaves every one of them exactly as it was, so there was nothing
 * in the polled set that could ever have carried this.
 *
 * The host is the loopback discard port, which refuses immediately and leaves
 * the machine's network alone. A blackholed address would test the timeout as
 * well as the report, and those are two claims: this one is that a refused
 * open is REPORTED, which has to hold on a build machine with no network at
 * all.
 */
class MpvOpenFailureTest {

    @Test
    fun anUnreachableStreamReportsAnErrorInsteadOfLoadingForever() {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) return println("SKIPPED: libmpv unavailable — $reason")

        assertNotNull(failureFromARefusedOpen(), "a refused open produced no stream:error within ${BUDGET_MS}ms")
    }

    // Separated from the test so the early skip above is a plain return rather
    // than a label out of a coroutine builder.
    private fun failureFromARefusedOpen(): String? = runBlocking {
        val engine = MpvVideoBackend()
        var failure: String? = null
        try {
            engine.on(BackendEvents.STREAM_ERROR) { payload -> failure = payload?.toString() }
            engine.load(REFUSED, LoadOptions(autoplay = true))

            val deadline: Long = System.currentTimeMillis() + BUDGET_MS
            while (failure == null && System.currentTimeMillis() < deadline) delay(POLL_MS)
        } finally {
            engine.release()
        }

        failure
    }

    private companion object {
        const val REFUSED: String = "http://127.0.0.1:9/nothing/is/here.m3u8"
        const val BUDGET_MS: Long = 30_000
        const val POLL_MS: Long = 50
    }
}
