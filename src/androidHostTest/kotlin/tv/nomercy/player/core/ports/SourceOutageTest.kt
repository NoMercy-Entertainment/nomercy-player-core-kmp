// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceOutageTest {

    @Test
    fun `a restarting server's bad status is transient`() {
        assertTrue(SourceOutage.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(SourceOutage.isTransient(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(SourceOutage.isTransient(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun `a broken decode is not an outage`() {
        assertFalse(SourceOutage.isTransient(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(SourceOutage.isTransient(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test
    fun `cloudflared's 5xx earns the full ladder with no connection failure in front of it`() {
        assertTrue(SourceOutage.isOriginDownStatus(530))
        assertTrue(SourceOutage.isOriginDownStatus(502))
        assertFalse(SourceOutage.isOriginDownStatus(404))

        assertEquals(
            SourceOutage.BACKOFF_MS.size,
            SourceOutage.retryLimitFor(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatus = 530,
                sawConnectionFailure = false,
            ),
        )
    }

    @Test
    fun `a cold 404 does not retry at all, the same 404 after a refused connection rides the outage out`() {
        // Was five rungs — thirty seconds of spinner on an episode that was
        // never encoded, ending in a message that told the viewer nothing they
        // could act on. The server answered definitively the first time.
        assertEquals(
            SourceOutage.NO_RETRIES,
            SourceOutage.retryLimitFor(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                httpStatus = 404,
                sawConnectionFailure = false,
            ),
        )
        assertEquals(
            SourceOutage.BACKOFF_MS.size,
            SourceOutage.retryLimitFor(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                httpStatus = 404,
                sawConnectionFailure = true,
            ),
        )
    }

    // The status read out of a real InvalidResponseCodeException needs a DataSpec,
    // and building one calls Uri.parse — stubbed to throw on a host JVM. That half
    // lives in SourceOutageDeviceTest; this is the "no response anywhere" half,
    // which is what decides whether a 530 gets the full ladder or five rungs.
    @Test
    fun `a failure carrying no http response reads as status zero`() {
        assertEquals(0, SourceOutage.httpStatusOf(IllegalStateException("nothing http here")))
        assertEquals(0, SourceOutage.httpStatusOf(null))
    }

    @Test
    fun `the ladder's length is the give-up budget`() {
        assertEquals(105_000L, SourceOutage.budgetMs())
    }

    @Test
    fun `404 and 410 are the server saying the media is absent`() {
        assertTrue(SourceOutage.isMediaAbsentStatus(404))
        assertTrue(SourceOutage.isMediaAbsentStatus(410))
    }

    @Test
    fun `a status that is not a definite absence is not treated as one`() {
        // 403 and 401 are about who is asking, not whether the file exists, and
        // 500 is the server failing rather than answering. Folding any of them
        // into "absent" would end a session that a retry or a re-auth fixes.
        listOf(0, 401, 403, 408, 429, 500, 502, 503, 504, 520, 530).forEach { status ->
            assertFalse(SourceOutage.isMediaAbsentStatus(status), "$status must not read as an absent file")
        }
    }

    @Test
    fun `an outage status still gets the whole ladder while an absent file gets none`() {
        // The two verdicts must not collapse into each other: a restarting
        // server and a missing episode both arrive as a bad HTTP status, and
        // one of them is worth waiting for.
        val absent: Int = SourceOutage.retryLimitFor(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatus = 404,
            sawConnectionFailure = false,
        )
        val originDown: Int = SourceOutage.retryLimitFor(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatus = 503,
            sawConnectionFailure = false,
        )

        assertEquals(SourceOutage.NO_RETRIES, absent)
        assertEquals(SourceOutage.BACKOFF_MS.size, originDown)
    }

    @Test
    fun `a 403 still gets its rungs rather than being read as a missing file`() {
        // Our own abuse guard answers a bare 403, and that is a session worth
        // retrying, not an episode to skip past.
        assertEquals(
            SourceOutage.HTTP_STATUS_RETRY_LIMIT,
            SourceOutage.retryLimitFor(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatus = 403,
                sawConnectionFailure = false,
            ),
        )
    }
}
