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
        httpStatus in CLOUDFLARE_ORIGIN_ERROR_RANGE ||
            httpStatus == HTTP_BAD_GATEWAY ||
            httpStatus == HTTP_SERVICE_UNAVAILABLE ||
            httpStatus == HTTP_GATEWAY_TIMEOUT

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
     * True for a status that means the server answered, and answered that this
     * media does not exist.
     *
     * An episode that has not been encoded yet answers 404, and it will answer
     * 404 to every retry: the file is not late, it is absent. Waiting is not a
     * strategy for it, and the viewer is the only one who can decide what to do
     * instead. 410 is the same answer said more firmly.
     */
    public fun isMediaAbsentStatus(httpStatus: Int): Boolean =
        httpStatus == HTTP_NOT_FOUND || httpStatus == HTTP_GONE

    /**
     * How many rungs this failure is allowed.
     *
     * The full ladder is for an outage: a connection that was refused, or a
     * status that says the origin is down. A bare 4xx is the server answering
     * that this file is not there.
     *
     * A 404 gets NONE. It used to get five, which is thirty seconds of spinner
     * on an episode that was never encoded, and at the end of it the viewer was
     * told nothing useful — Stoney, watching exactly that: "this video is not
     * encoded yet and should throw a 404 and skip to the next". A definite
     * answer is worth surfacing the moment it arrives.
     *
     * [httpStatus] is 0 when the failure carried no HTTP response.
     */
    public fun retryLimitFor(errorCode: Int, httpStatus: Int, sawConnectionFailure: Boolean): Int = when {
        // A connection failure first means the host went away; the 404 that
        // follows is a route table still warming up, not a missing file.
        sawConnectionFailure -> BACKOFF_MS.size
        isOriginDownStatus(httpStatus) -> BACKOFF_MS.size
        isMediaAbsentStatus(httpStatus) -> NO_RETRIES

        errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> HTTP_STATUS_RETRY_LIMIT

        else -> BACKOFF_MS.size
    }

    /** No rung at all: the answer will not change by asking again. */
    public const val NO_RETRIES: Int = 0

    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410

    // Cloudflare answers 52x from the EDGE when the origin behind it is not
    // reachable — see isOriginDownStatus for why that, not a connection error,
    // is the shape a NoMercy server restart actually takes.
    private val CLOUDFLARE_ORIGIN_ERROR_RANGE = 520..530
    private const val HTTP_BAD_GATEWAY = 502
    private const val HTTP_SERVICE_UNAVAILABLE = 503
    private const val HTTP_GATEWAY_TIMEOUT = 504

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
