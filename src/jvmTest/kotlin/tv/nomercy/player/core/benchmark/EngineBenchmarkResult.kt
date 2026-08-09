// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

/**
 * One fixture, through one engine, with the numbers a viewer would feel.
 *
 * Kept as a record rather than printed where it is measured so the report can be
 * ordered, compared across engines and totalled. A benchmark that prints as it
 * goes is a benchmark whose two halves cannot be put side by side, which is the
 * whole reason this exists.
 *
 * Null means not observed, and it prints as a dash rather than as zero. Zero
 * milliseconds to first picture and no picture at all are opposite results and
 * a report that renders them identically is worse than no report.
 */
internal data class EngineBenchmarkResult(
    val engineId: String,
    val fixtureId: String,
    val metadataMs: Long?,
    val canPlayMs: Long?,
    val firstPictureMs: Long?,
    val durationSeconds: Double,
    val framesPerSecond: Double,
    val seekMs: Long?,
    val waitingCount: Int,
    val memoryMb: Long,
    val missingEvents: List<String>,
    val failure: String?,
) {

    /** Whether a viewer would call this playable: a picture, a length, and a seek that lands. */
    val playable: Boolean
        get() = firstPictureMs != null && durationSeconds > 0.0 && seekMs != null

    fun row(): String = buildString {
        append(if (playable) "  ok  " else "  --  ")
        append(fixtureId.padEnd(COLUMN_ID))
        append(millis(metadataMs))
        append(millis(canPlayMs))
        append(millis(firstPictureMs))
        append(millis(seekMs))
        append("%.1f".format(framesPerSecond).padStart(COLUMN_NUMBER))
        append(waitingCount.toString().padStart(COLUMN_NUMBER))
        append("${memoryMb}M".padStart(COLUMN_NUMBER))
        failure?.let { message -> append("  $message") }
        if (missingEvents.isNotEmpty()) append("  never: ${missingEvents.joinToString(",")}")
    }

    private fun millis(value: Long?): String =
        (value?.let { measured -> "$measured" } ?: "-").padStart(COLUMN_NUMBER)

    companion object {
        fun header(): String = buildString {
            append(" ".repeat(HEADER_VERDICT))
            append("fixture".padEnd(COLUMN_ID))
            append("meta".padStart(COLUMN_NUMBER))
            append("canplay".padStart(COLUMN_NUMBER))
            append("picture".padStart(COLUMN_NUMBER))
            append("seek".padStart(COLUMN_NUMBER))
            append("fps".padStart(COLUMN_NUMBER))
            append("waits".padStart(COLUMN_NUMBER))
            append("rss".padStart(COLUMN_NUMBER))
        }

        private const val HEADER_VERDICT: Int = 6
        private const val COLUMN_ID: Int = 24
        private const val COLUMN_NUMBER: Int = 9
    }
}
