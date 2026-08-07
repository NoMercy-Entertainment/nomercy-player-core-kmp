// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * Does mpv's software renderer actually produce a picture.
 *
 * Off-screen, and asserting on PIXELS rather than on a call returning success.
 * The whole failure this gate exists for looked like success from every other
 * angle: the engine played, the playhead moved, subtitles drew, and the pane was
 * black — because `mpv_render_context_render` was being handed a stride in the
 * wrong width of integer and refused every frame with "invalid parameter".
 */
class MpvRenderGateTest {

    private class CountingSink : VideoFrameSink {
        var frames: Int = 0
        var lit: Int = 0
        var width: Int = 0
        var height: Int = 0

        override fun format(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        // A frame is not a picture. A renderer that hands over the buffer it was
        // given without drawing into it delivers frames all day and the screen
        // stays black, so the pixels are counted rather than the calls.
        override fun display(picture: ByteBuffer) {
            frames += 1
            var seen = 0
            var index = 0
            while (index < picture.limit()) {
                if (picture.get(index) != 0.toByte()) seen += 1
                index += SAMPLE_STEP
            }
            if (seen > 0) lit += 1
        }
    }

    private var backend: MpvVideoBackend? = null

    @AfterTest
    fun releaseTheEngine() {
        backend?.release()
        backend = null
    }

    @Test
    fun aPlayingEngineDrawsPixelsIntoTheSink() {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) return println("SKIPPED: libmpv unavailable — $reason")

        val fixture: String? = System.getProperty("nomercy.mpv.fixture")
        if (fixture == null) return println("SKIPPED: no -Dnomercy.mpv.fixture=<file or url>")

        drawFrom(fixture)
    }

    private fun drawFrom(fixture: String) = runBlocking {
        val engine = MpvVideoBackend().also { backend = it }
        val sink = CountingSink()
        engine.videoFrameSink(sink)

        engine.load(fixture, LoadOptions(autoplay = true))
        delay(SETTLE_MS)

        assertTrue(sink.frames > 0, "no frame reached the sink at all")
        assertTrue(sink.width > 0 && sink.height > 0, "the sink was never told a picture size")
        assertTrue(sink.lit > 0, "${sink.frames} frames arrived and every one of them was black")
    }

    private companion object {
        const val SETTLE_MS: Long = 12_000L

        // Every 997th byte: a prime stride so a sample cannot land on the same
        // channel of every pixel, which on a black-with-one-colour frame is how
        // a sampler concludes "all zero" from a picture that is not.
        const val SAMPLE_STEP: Int = 997
    }
}
