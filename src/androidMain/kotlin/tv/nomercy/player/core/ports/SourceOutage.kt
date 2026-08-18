// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * Telling "the bytes are not there right now" apart from "this media is broken".
 *
 * A media-server restart produces two failure shapes in one outage:
 * connection-refused while the host is down, then a bad HTTP status the moment
 * it is listening again but has not finished mapping its routes. Treating only
 * the first as recoverable ends the restart on a 404 that no reconnect ladder
 * ever sees, and the session dies under an error overlay while the server is
 * two seconds from answering again.
 *
 * Separate from the backend so the decision can be checked without a device:
 * every one of these answers is a number-to-verdict lookup, and a lookup that
 * has drifted is invisible in a screenshot — playback still fails, it just
 * fails for a reason nobody chose.
 */
public object SourceOutage {

    /**
     * Backoff for re-preparing after the server drops out mid-playback. Starts
     * tight so a momentary blip is invisible, then settles to a steady 15 s
     * poll — long enough to sit out a .NET host restart with EF Core warmup and
     * plugin reload without hammering a server that is still booting. The
     * ladder's length IS the give-up budget (~105 s); past that the failure is
     * real and the error surfaces.
     */
    public val BACKOFF_MS: LongArray = longArrayOf(
        1_000,
        2_000,
        4_000,
        8_000,
        15_000,
        15_000,
        15_000,
        15_000,
        15_000,
        15_000,
    )

    /**
     * Rungs an HTTP-status failure gets when nothing connection-level preceded
     * it (~30 s). A video whose URL is genuinely gone answers 404 identically
     * every time, and making the viewer watch a spinner for the full budget
     * before being told so is its own bug.
     */
    public const val HTTP_STATUS_RETRY_LIMIT: Int = 5

    /** Total wall-clock the ladder covers before a failure is treated as real. */
    public fun budgetMs(): Long = BACKOFF_MS.sum()

    /**
     * True for a status that means "the server is not there", as opposed to
     * "the server is there and this file is not".
     *
     * Measured on a real outage: a NoMercy server behind cloudflared never
     * refuses a connection while it restarts — the edge answers 530, and 52x
     * for its other failure modes. So the shape a restart actually takes is a
     * bad HTTP status with no connection error anywhere in front of it, which
     * is exactly the case a connection-failure-gated budget would cut short.
     */
    public fun isOriginDownStatus(httpStatus: Int): Boolean =
        httpStatus in 520..530 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504

    /**
     * True for the source failures that mean "the bytes are not there right
     * now", as opposed to "this media is broken".
     */
    public fun isTransient(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> true

        else -> false
    }

    /**
     * How many rungs this failure is allowed.
     *
     * The full ladder is for an outage: a connection that was refused, or a
     * status that says the origin is down. A bare 4xx is the server answering
     * that this file is not there.
     *
     * [httpStatus] is 0 when the failure carried no HTTP response.
     */
    public fun retryLimitFor(errorCode: Int, httpStatus: Int, sawConnectionFailure: Boolean): Int = when {
        sawConnectionFailure -> BACKOFF_MS.size
        isOriginDownStatus(httpStatus) -> BACKOFF_MS.size

        errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> HTTP_STATUS_RETRY_LIMIT

        else -> BACKOFF_MS.size
    }

    /** The HTTP status Media3 gave up on, or 0 when the failure carried no response. */
    public fun httpStatusOf(error: Throwable?): Int {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < CAUSE_DEPTH_LIMIT) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
            depth++
        }
        return 0
    }

    private const val CAUSE_DEPTH_LIMIT = 8
}
