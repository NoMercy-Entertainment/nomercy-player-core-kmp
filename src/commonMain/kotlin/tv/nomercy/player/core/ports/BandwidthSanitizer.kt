// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The lookbehind is the whole difference between this and a rewrite of
// AVERAGE-BANDWIDTH as well. "BANDWIDTH=" is a substring of
// "AVERAGE-BANDWIDTH=", so an unanchored pattern silently caps the average too
// — which changes how a player estimates a variant's cost without changing the
// variant, and is the kind of thing that shows as adaptation behaving oddly on
// one stream.
private val BANDWIDTH = Regex("""(?<![-A-Z])(BANDWIDTH=)(\d+)""")

// BANDWIDTH values in an HLS master playlist, brought into a range a parser and
// a device can both survive.
//
// Three separate problems wear one costume here. A value above Int.MAX_VALUE
// overflows Media3's parser and the whole manifest fails to load — not the
// variant, the manifest, so nothing plays. A value above what the device can
// decode invites adaptation into a rung that stutters. And a value below any
// sensible floor makes a rung look free, so adaptation parks on it and the
// viewer watches the worst copy on a good connection.
//
// Text in, text out, no HTTP: the rule is the part worth being sure about and it
// is the part a transport cannot help testing. The interceptor that uses it is a
// dozen lines.
public object BandwidthSanitizer {

    public data class Result(
        val playlist: String,
        val adjustments: List<Adjustment>,
    ) {
        public val changed: Boolean get() = adjustments.isNotEmpty()
    }

    public data class Adjustment(val from: Long, val to: Long)

    // [ceiling] is what the device can actually decode; [floor] is the lowest
    // rung worth adapting to.
    public fun sanitize(playlist: String, ceiling: Long, floor: Long = DEFAULT_FLOOR): Result {
        val adjustments: MutableList<Adjustment> = mutableListOf()

        val rewritten: String = BANDWIDTH.replace(playlist) { match ->
            val key: String = match.groupValues[1]
            // A value too long for a Long is already past every limit below, so
            // it becomes the floor rather than the ceiling: a rung whose
            // bandwidth cannot be parsed is not a rung anyone should adapt into.
            val original: Long = match.groupValues[2].toLongOrNull() ?: floor
            val adjusted: Long = clamp(original, ceiling, floor)

            if (adjusted != original) adjustments += Adjustment(original, adjusted)
            "$key$adjusted"
        }

        return Result(rewritten, adjustments)
    }

    private fun clamp(value: Long, ceiling: Long, floor: Long): Long {
        // Int.MAX_VALUE first and unconditionally, because that limit is the
        // parser's rather than the device's: exceeding it does not degrade
        // playback, it stops the manifest being read at all.
        val parseable: Long = minOf(value, Int.MAX_VALUE.toLong())
        val underCeiling: Long = minOf(parseable, ceiling)
        return maxOf(underCeiling, floor)
    }

    // 300 kbps. Below this a rung is not a viewing experience, and leaving one
    // in the ladder means adaptation choosing it the moment the connection
    // wobbles.
    public const val DEFAULT_FLOOR: Long = 300_000L
}
