// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Runs [AdaptiveLadder] against an engine that does not adapt.
 *
 * One tick reads the engine's own bandwidth measurement, asks the ladder what
 * should be playing, and switches only when the answer changed. Driven from
 * outside — a timer, a `timeupdate`, a resize — because a library that starts
 * its own clock is a library a consumer cannot stop, and every platform here
 * already has a tick of its own.
 *
 * MANUAL is honoured by doing nothing at all. A viewer who picked 720p has
 * picked 720p, and an adaptation loop that quietly climbs off their choice is
 * the defect this class would otherwise introduce.
 */
public class AdaptiveLadderDriver(
    private val backend: VideoBackend,
    private val displayHdr: () -> Boolean = { true },
) {

    private var paneWidthPx: Int = 0
    private var paneHeightPx: Int = 0

    /** AUTO until a viewer chooses, which is the reference's default. */
    public var mode: QualityMode = QualityMode.AUTO
        private set

    /** What the driver last asked the engine to play. */
    public var applied: QualityLevel? = null
        private set

    /**
     * The space the picture is drawn in.
     *
     * Recorded rather than acted on, because a resize arrives while the pane is
     * still settling and switching a rendition per animation frame is a
     * re-buffer per animation frame. The next tick applies it.
     */
    public fun surfaceSize(widthPx: Int, heightPx: Int) {
        paneWidthPx = widthPx
        paneHeightPx = heightPx
    }

    /** A viewer's choice. Null hands adaptation back. */
    public fun choose(level: QualityLevel?) {
        mode = if (level == null) QualityMode.AUTO else QualityMode.MANUAL
        applied = level
        backend.quality(level)
    }

    /**
     * One adaptation step. Returns the rung now playing, or null when there was
     * nothing to choose from.
     *
     * The engine is only touched when the answer CHANGED. Re-selecting the rung
     * that is already playing is not free on any engine here — libVLC reopens
     * the stream, mpv restarts the demuxer — so an idempotent-looking call
     * would stutter the picture once per tick.
     */
    public fun tick(): QualityLevel? {
        if (mode == QualityMode.MANUAL) return applied

        val wanted: QualityLevel? = AdaptiveLadder.pick(
            levels = backend.qualityLevels(),
            bandwidthBps = backend.bandwidthEstimate(),
            paneWidthPx = paneWidthPx,
            paneHeightPx = paneHeightPx,
            displayHdr = displayHdr(),
            current = applied ?: backend.quality(),
        )

        if (wanted != null && wanted != applied) {
            applied = wanted
            backend.quality(wanted)
        }
        return applied
    }
}
