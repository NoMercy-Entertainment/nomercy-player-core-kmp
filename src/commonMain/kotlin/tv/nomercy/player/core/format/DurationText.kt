// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.format

/**
 * A duration, as a person reads it.
 *
 * Core rather than video, because the web declares it in `core/format.ts` and
 * both players show durations — a music playlist lists track lengths with the
 * same rules a video progress bar does. It lived in the video package's `tv`
 * directory until this was measured, where six of its eight callers were the
 * desktop chrome rather than the television one, and music could not reach it
 * at all.
 */

/**
 * `M:SS`, or `H:MM:SS` once there is an hour.
 *
 * Hours only when there are any. A twenty minute episode showing 0:20:00 makes
 * somebody count the fields to work out which is which, and every player they
 * have ever used writes it the short way.
 */
public fun formatSeconds(seconds: Double): String {
    // Non-finite and negative both read as zero.
    //
    // Negative arrives from a remaining-time subtraction that crossed zero at
    // the end of a file, and a bar reading "-0:-1" gets reported as a playback
    // bug rather than a formatting one.
    //
    // Infinite arrives from a live stream, whose duration genuinely is. Kotlin
    // SATURATES on the conversion rather than overflowing, so the naive version
    // of this rendered Int.MAX_VALUE seconds as "596523:14:07" — a number that
    // looks like a corrupted file rather than a stream without an end.
    if (!seconds.isFinite() || seconds < 0.0) return ZERO

    val whole: Int = seconds.toInt()
    val hours: Int = whole / SECONDS_PER_HOUR
    val minutes: Int = (whole % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val remainder: Int = whole % SECONDS_PER_MINUTE

    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(remainder)}"
    } else {
        "$minutes:${twoDigits(remainder)}"
    }
}

/**
 * A duration for a menu label, from either a number of seconds or the wire.
 *
 * The server sends `"00:24:14"` for an episode, and showing that verbatim beside
 * a list of tracks written `24:14` makes one row look like a different unit. The
 * leading zero hour is stripped rather than the string being reparsed, because a
 * string that came formatted is not this function's to reinterpret.
 *
 * Empty, not `"0:00"`, when there is nothing to show: a label is absent in that
 * case, and a playlist row claiming every unknown-length track is zero seconds
 * long reads as a library that failed to scan.
 */
public fun formatDuration(seconds: Double?): String = when {
    seconds == null || !seconds.isFinite() || seconds <= 0.0 -> ""
    else -> formatSeconds(seconds)
}

/** The wire's own `HH:MM:SS`, with a zero hours field dropped. */
public fun formatDuration(wire: String?): String =
    wire?.removePrefix(LEADING_ZERO_HOUR).orEmpty()

// Minutes and seconds always carry two digits once there is a field to their
// left, or 1:5 reads as one minute five rather than one minute and five seconds.
private fun twoDigits(value: Int): String = if (value < TEN) "0$value" else "$value"

private const val ZERO = "0:00"
private const val LEADING_ZERO_HOUR = "00:"
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val TEN = 10
