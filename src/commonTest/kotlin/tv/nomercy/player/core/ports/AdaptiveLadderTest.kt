// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The adaptation decision, without a stream.
//
// bipbop's ladder, because it is the one every engine claim in this campaign was
// measured against and the one the quality menu is read from.
private val LADDER: List<QualityLevel> = listOf(
    QualityLevel(height = 234, bitrate = 200_000, codec = "avc1", width = 416),
    QualityLevel(height = 360, bitrate = 600_000, codec = "avc1", width = 640),
    QualityLevel(height = 540, bitrate = 1_500_000, codec = "avc1", width = 960),
    QualityLevel(height = 720, bitrate = 3_000_000, codec = "avc1", width = 1280),
    QualityLevel(height = 1080, bitrate = 6_000_000, codec = "avc1", width = 1920),
)

private val HDR_TOP = QualityLevel(
    height = 2160,
    bitrate = 16_000_000,
    codec = "hvc1",
    dynamicRange = DynamicRange.HDR10,
    width = 3840,
)

class AdaptiveLadderTest {

    @Test
    fun anEngineThatMeasuresNothingGetsTheBottomRung() {
        // Zero is not "unknown, assume fast". An engine that cannot say how
        // fast the connection is has not said it is fast, and starting at the
        // top on no evidence is a stall in front of the viewer's first frame.
        assertEquals(234, AdaptiveLadder.pick(LADDER, bandwidthBps = 0)?.height)
    }

    @Test
    fun theBestRungTheConnectionAffordsIsChosen() {
        assertEquals(540, AdaptiveLadder.pick(LADDER, bandwidthBps = 2_000_000)?.height)
    }

    @Test
    fun climbingNeedsMarginAndDroppingDoesNot() {
        // 3.0 Mbit exactly covers the 720p rung and does not cover it with the
        // margin, so a player at 540p stays there rather than trading places
        // with 720p every few seconds — each switch being a re-buffer the
        // viewer sees.
        val current: QualityLevel = LADDER.first { it.height == 540 }
        assertEquals(540, AdaptiveLadder.pick(LADDER, 3_000_000, current = current)?.height)
        assertEquals(720, AdaptiveLadder.pick(LADDER, 4_000_000, current = current)?.height)

        // Downwards there is no margin at all: a rung that no longer fits is
        // already stalling.
        val high: QualityLevel = LADDER.first { it.height == 1080 }
        assertEquals(720, AdaptiveLadder.pick(LADDER, 3_000_000, current = high)?.height)
    }

    @Test
    fun aPaneSmallerThanTheLadderCapsWhatAdaptationMayClimbTo() {
        // The measured desktop defect: a 1230px pane pulling a 4K rendition and
        // holding frame delivery at a third of the clip's rate, for pixels
        // thrown away on the way to the screen.
        val picked: QualityLevel? = AdaptiveLadder.pick(
            LADDER,
            bandwidthBps = 50_000_000,
            paneWidthPx = 960,
            paneHeightPx = 540,
        )

        assertEquals(540, picked?.height, "adaptation climbed past the pane")
    }

    @Test
    fun anSdrDisplayNeverClimbsIntoAnHdrRung() {
        val withHdr: List<QualityLevel> = LADDER + HDR_TOP

        val picked: QualityLevel? = AdaptiveLadder.pick(withHdr, bandwidthBps = 50_000_000, displayHdr = false)

        assertEquals(1080, picked?.height)
        assertEquals(DynamicRange.SDR, picked?.dynamicRange)
    }

    @Test
    fun anHdrDisplayMayHaveTheHdrRung() {
        val withHdr: List<QualityLevel> = LADDER + HDR_TOP

        assertEquals(2160, AdaptiveLadder.pick(withHdr, bandwidthBps = 50_000_000, displayHdr = true)?.height)
    }

    @Test
    fun aRungWithNoDeclaredBitrateIsNotTreatedAsFree() {
        // An HLS master always declares BANDWIDTH; a container's own tracks do
        // not, and the desktop reads both. A zero must not read as "costs
        // nothing", which would park every engine on it.
        val unpriced: List<QualityLevel> = listOf(QualityLevel(height = 1080, bitrate = 0, codec = "avc1"))

        assertEquals(1080, AdaptiveLadder.pick(unpriced, bandwidthBps = 0)?.height)
    }

    @Test
    fun anEmptyLadderIsNull() {
        assertNull(AdaptiveLadder.pick(emptyList(), bandwidthBps = 10_000_000))
    }
}

class AdaptiveLadderDriverTest {

    // Records what it was told to play, and answers a fixed ladder. Everything
    // else throws: a driver that reached for the playhead or the tracks would
    // be doing something this class has no business doing.
    private class RecordingBackend(
        private val levels: List<QualityLevel>,
        var bandwidth: Int,
    ) : VideoBackend {
        val selections: MutableList<QualityLevel?> = mutableListOf()
        private var pinned: QualityLevel? = null

        override fun qualityLevels(): List<QualityLevel> = levels
        override fun quality(): QualityLevel? = pinned
        override fun quality(level: QualityLevel?) {
            pinned = level
            selections += level
        }

        override fun bandwidthEstimate(): Int = bandwidth
        override fun audioTracks(): List<AudioTrack> = emptyList()
        override fun audioTrack(): AudioTrack? = null
        override fun audioTrack(track: AudioTrack): Unit = error("not this test's business")
        override fun subtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun subtitleTrack(): SubtitleTrack? = null
        override fun subtitleTrack(track: SubtitleTrack?): Unit = error("not this test's business")
        override suspend fun load(url: String, opts: LoadOptions): Unit = error("not this test's business")
        override suspend fun play(): Unit = error("not this test's business")
        override fun pause(): Unit = error("not this test's business")
        override fun stop(): Unit = error("not this test's business")
        override fun currentTime(): Double = 0.0
        override fun currentTime(seconds: Double): Unit = error("not this test's business")
        override fun duration(): Double = 0.0
        override fun volume(): Float = 1f
        override fun volume(value: Float): Unit = error("not this test's business")
        override fun mute(): Unit = error("not this test's business")
        override fun unmute(): Unit = error("not this test's business")
        override fun playbackRate(): Double = 1.0
        override fun playbackRate(rate: Double): Unit = error("not this test's business")
        override fun state(): BackendState = BackendState.PLAYING
        override fun on(event: String, fn: (Any?) -> Unit): Unit = Unit
        override fun off(event: String, fn: (Any?) -> Unit): Unit = Unit
    }

    @Test
    fun theEngineIsOnlyTouchedWhenTheAnswerChanged() {
        // Re-selecting the rung already playing is not free on any engine here:
        // libVLC reopens the stream and mpv restarts the demuxer, so a call
        // that looks idempotent stutters the picture once per tick.
        val backend = RecordingBackend(LADDER, bandwidth = 2_000_000)
        val driver = AdaptiveLadderDriver(backend)

        driver.tick()
        driver.tick()
        driver.tick()

        assertEquals(1, backend.selections.size, "the engine was re-told what it was already playing")
        assertEquals(540, backend.selections.single()?.height)
    }

    @Test
    fun aViewersChoiceIsNotClimbedOffAgain() {
        val backend = RecordingBackend(LADDER, bandwidth = 50_000_000)
        val driver = AdaptiveLadderDriver(backend)

        driver.choose(LADDER.first { it.height == 360 })
        driver.tick()
        driver.tick()

        assertEquals(QualityMode.MANUAL, driver.mode)
        assertEquals(listOf(360), backend.selections.map { it?.height })
    }

    @Test
    fun handingAdaptationBackResumesIt() {
        val backend = RecordingBackend(LADDER, bandwidth = 50_000_000)
        val driver = AdaptiveLadderDriver(backend)

        driver.choose(LADDER.first { it.height == 360 })
        driver.choose(null)
        driver.tick()

        assertEquals(QualityMode.AUTO, driver.mode)
        assertEquals(1080, driver.applied?.height)
    }

    @Test
    fun aMeasuredPaneCapsTheNextTickRatherThanTheCurrentFrame() {
        val backend = RecordingBackend(LADDER, bandwidth = 50_000_000)
        val driver = AdaptiveLadderDriver(backend)

        driver.tick()
        driver.surfaceSize(640, 360)
        driver.tick()

        assertEquals(listOf(1080, 360), backend.selections.map { it?.height })
        assertTrue(driver.applied?.height == 360)
    }
}
