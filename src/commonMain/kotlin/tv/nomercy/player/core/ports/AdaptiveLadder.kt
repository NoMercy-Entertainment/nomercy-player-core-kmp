// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Which rung to play, from what the connection is measured at and what the
 * display can use.
 *
 * Adaptation is OURS, on every engine. libVLC 3 adapts inside its demuxer and
 * cannot be told which variant to take, so the desktop faked a choice by
 * rewriting the master playlist and reopening the stream; libmpv selects a
 * variant precisely and does not adapt at all; a browser's media element sees
 * demuxed fragments and leaves the whole decision to hls.js. Three engines,
 * three different halves of the job — so the decision lives here, above all of
 * them, and an engine is asked only to play the rung it is given.
 *
 * Pure, and taking numbers rather than a backend, so every branch is provable
 * without a stream. [AdaptiveLadderDriver] is the part that reads an engine.
 */
public object AdaptiveLadder {

    /**
     * The rung to play now, or null when the ladder has nothing to choose from.
     *
     * [bandwidthBps] is what the engine measures, zero when it does not measure
     * at all — and zero picks the bottom rung rather than the top. An engine
     * that cannot say how fast the connection is has not said it is fast.
     */
    public fun pick(
        levels: List<QualityLevel>,
        bandwidthBps: Int,
        paneWidthPx: Int = 0,
        paneHeightPx: Int = 0,
        displayHdr: Boolean = true,
        current: QualityLevel? = null,
    ): QualityLevel? {
        if (levels.isEmpty()) return null

        val ceiling: QualityLevel? = SizeAbrConstraint.narrower(
            SizeAbrConstraint.abrCeiling(levels, paneWidthPx, paneHeightPx),
            HdrAbrConstraint.abrCeiling(levels, displayHdr),
        )
        val allowed: List<QualityLevel> = levels.filter { rung -> ceiling == null || rung.notAbove(ceiling) }
        if (allowed.isEmpty()) return levels.minWithOrNull(LADDER_ORDER)

        // Hysteresis, and only upwards. Climbing on a measurement that barely
        // covers the next rung is how a player oscillates between two
        // renditions for a whole film — each switch is a re-buffer the viewer
        // sees. Dropping has no margin on purpose: a rung that no longer fits
        // is already stalling, and waiting for it to be comfortably wrong costs
        // the viewer the stall.
        val budget: Double = bandwidthBps.toDouble()
        val affordable: List<QualityLevel> = allowed.filter { rung ->
            val margin: Double = if (current == null || rung.above(current)) CLIMB_MARGIN else 1.0
            rung.bitrate <= 0 || rung.bitrate * margin <= budget
        }

        return affordable.maxWithOrNull(LADDER_ORDER) ?: allowed.minWithOrNull(LADDER_ORDER)
    }

    // Height first, then bitrate — the ordering a quality menu uses, so "the
    // best rung" means the same thing to the picker and to the viewer.
    private val LADDER_ORDER = compareBy<QualityLevel> { it.height }.thenBy { it.bitrate }

    private fun QualityLevel.above(other: QualityLevel): Boolean = LADDER_ORDER.compare(this, other) > 0

    private fun QualityLevel.notAbove(other: QualityLevel): Boolean = LADDER_ORDER.compare(this, other) <= 0

    /**
     * How much more bandwidth than a rung costs before adaptation climbs into
     * it. The web kit's own margin, and the reason two rungs of similar cost do
     * not trade places every few seconds.
     */
    public const val CLIMB_MARGIN: Double = 1.3
}
