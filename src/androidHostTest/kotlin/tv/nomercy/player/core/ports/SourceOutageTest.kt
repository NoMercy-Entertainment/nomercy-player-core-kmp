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
    fun `a cold 404 fails fast, the same 404 after a refused connection rides the outage out`() {
        assertEquals(
            SourceOutage.HTTP_STATUS_RETRY_LIMIT,
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
}
