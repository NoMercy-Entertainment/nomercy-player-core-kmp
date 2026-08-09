// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import kotlinx.coroutines.delay
import tv.nomercy.player.core.ports.BackendEvents
import tv.nomercy.player.core.ports.FrameSourceBackend
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.MediaBackend
import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.core.ports.engines.VideoEngineProvider

/**
 * One fixture through one engine, timed at the points a viewer notices.
 *
 * It waits for outcomes rather than for the clock. The gate this replaces slept
 * a flat twenty seconds per fixture and then asked whether a frame had arrived,
 * which answers "does it eventually play" and cannot answer "how long did the
 * viewer stare at nothing" — and the second question is the one being asked
 * about the desktop engine. Waiting for the outcome makes the elapsed time the
 * measurement instead of a constant somebody chose.
 *
 * The deadline is a ceiling, not a duration. A fixture that never produces a
 * picture stops costing time the moment nothing more can change.
 */
internal class EngineBenchmarkRun(
    private val provider: VideoEngineProvider,
    private val openBudgetMs: Long,
    private val seekBudgetMs: Long,
) {

    suspend fun measure(fixtureId: String, url: String): EngineBenchmarkResult {
        val backend: VideoBackend = provider.create() as VideoBackend
        val pictures = PictureClock()
        val milestones = PlaybackMilestones(backend).attach()

        try {
            (backend as? FrameSourceBackend)?.videoFrameSink(pictures)

            pictures.mark()
            backend.load(url, LoadOptions(autoplay = true))
            awaitOpen(backend, pictures, milestones)

            val opened = Opened(
                metadataMs = milestones.at(BackendEvents.LOADED_METADATA),
                canPlayMs = milestones.at(BackendEvents.CAN_PLAY),
                firstPictureMs = pictures.firstPictureMs(),
                duration = backend.duration(),
            )

            val steady: Double = measureSteadyRate(pictures)
            val seekMs: Long? = measureSeek(backend, pictures)

            return EngineBenchmarkResult(
                engineId = provider.id,
                fixtureId = fixtureId,
                metadataMs = opened.metadataMs,
                canPlayMs = opened.canPlayMs,
                firstPictureMs = opened.firstPictureMs,
                durationSeconds = opened.duration,
                framesPerSecond = steady,
                seekMs = seekMs,
                waitingCount = milestones.count(BackendEvents.WAITING),
                memoryMb = ProcessMemory.residentMb(),
                missingEvents = milestones.missing(),
                failure = milestones.failure,
            )
        } finally {
            milestones.detach()
            backend.release()
        }
    }

    private class Opened(
        val metadataMs: Long?,
        val canPlayMs: Long?,
        val firstPictureMs: Long?,
        val duration: Double,
    )

    // Open is done when the viewer has both halves of it: a picture on screen
    // and a length under the scrubber. Either one alone is a player that looks
    // loaded and is not.
    private suspend fun awaitOpen(
        backend: MediaBackend,
        pictures: PictureClock,
        milestones: PlaybackMilestones,
    ) {
        val deadline: Long = System.nanoTime() + openBudgetMs * NANOS_PER_MS
        while (System.nanoTime() < deadline) {
            val hasPicture: Boolean = pictures.firstPictureMs() != null
            val hasLength: Boolean = backend.duration().isFinite() && backend.duration() > 0.0
            if (hasPicture && hasLength) return
            if (milestones.failure != null) return
            delay(POLL_MS)
        }
    }

    // Measured after the open has settled, so the rate is playback and not the
    // burst a decoder emits while it fills its queue.
    private suspend fun measureSteadyRate(pictures: PictureClock): Double {
        delay(SETTLE_MS)
        pictures.mark()
        delay(STEADY_MS)
        return pictures.framesPerSecond()
    }

    // A seek is done when the playhead has moved AND a picture from the new
    // position has been drawn. Landing the playhead alone is what makes a scrub
    // feel broken: the number under the thumb is right and the screen is stale.
    private suspend fun measureSeek(backend: MediaBackend, pictures: PictureClock): Long? {
        pictures.mark()
        backend.currentTime(SEEK_TO)

        val deadline: Long = System.nanoTime() + seekBudgetMs * NANOS_PER_MS
        while (System.nanoTime() < deadline) {
            val landed: Boolean = backend.currentTime() > SEEK_TO - SEEK_TOLERANCE
            val drawn: Long? = pictures.firstPictureMs()
            if (landed && drawn != null) return drawn
            delay(POLL_MS)
        }
        return null
    }

    private companion object {
        const val POLL_MS: Long = 25
        const val SETTLE_MS: Long = 500
        const val STEADY_MS: Long = 2_000

        // Far enough in to be past any title card and inside every fixture: the
        // shortest of them runs about ninety seconds.
        const val SEEK_TO: Double = 30.0
        const val SEEK_TOLERANCE: Double = 5.0
        const val NANOS_PER_MS: Long = 1_000_000
    }
}
