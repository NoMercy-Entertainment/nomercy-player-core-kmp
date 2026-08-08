// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * One rung of an HLS ladder, read out of the sentence mpv writes for it.
 *
 * mpv does not expose a variant's attributes as fields. It exposes an edition
 * with a TITLE, and the title is prose:
 *
 *     Bitrate: 1.928 Mbps (h264 [Main] 960x720 29.9167 fps 1928 kbps) (aac [LC] 2ch 22050 Hz 1928 kbps)
 *
 * Which is why this is a file with a test and not three regexes inline. The
 * first shape those took read the bitrate as "any number of five digits or
 * more" and matched **22050** — the audio SAMPLE RATE — on every rung of Apple's
 * bipbop ladder, so adaptation would have compared five renditions that all
 * cost 22 kbit and picked the tallest every time regardless of the connection.
 * The titles above are verbatim from a real run, not imagined.
 */
public object MpvEditionTitle {

    public fun parse(title: String): QualityLevel {
        val dimensions: MatchResult? = DIMENSIONS.find(title)

        return QualityLevel(
            height = dimensions?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            bitrate = bitrateOf(title),
            codec = CODEC.find(title)?.groupValues?.get(1).orEmpty(),
            width = dimensions?.groupValues?.get(1)?.toIntOrNull(),
            label = title.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Bits per second, from the leading "Bitrate:" clause and its unit.
     *
     * The LEADING one on purpose: the per-stream figures in the brackets are
     * that stream's share, and on the rung above they both read 1928 kbps for a
     * variant that costs 1928 kbps in total — adding them would double it.
     * Zero when the title carries no bitrate at all, which the ladder reads as
     * "not priced" rather than "free".
     */
    private fun bitrateOf(title: String): Int {
        val match: MatchResult = BITRATE.find(title) ?: return 0
        val value: Double = match.groupValues[1].toDoubleOrNull() ?: return 0
        val multiplier: Int = when (match.groupValues[2].lowercase()) {
            "gbps" -> GIGA
            "mbps" -> MEGA
            "kbps" -> KILO
            else -> 1
        }
        return (value * multiplier).toInt()
    }

    // "960x720", and never a frame rate or a sample rate: both numbers are
    // required and the separator is an x.
    private val DIMENSIONS = Regex("""(\d{2,5})x(\d{2,5})""")

    // The leading clause only, anchored on the word mpv writes before it.
    private val BITRATE = Regex("""Bitrate:\s*([\d.]+)\s*([KMG]?bps)""", RegexOption.IGNORE_CASE)

    // The first codec named in the brackets, which is the video codec when
    // there is one and the audio codec on an audio-only rung.
    private val CODEC = Regex("""\((\w+)\s""")

    private const val KILO: Int = 1_000
    private const val MEGA: Int = 1_000_000
    private const val GIGA: Int = 1_000_000_000
}
