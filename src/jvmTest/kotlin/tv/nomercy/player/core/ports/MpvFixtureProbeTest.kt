// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * Every fixture the testbed offers, through the mpv engine, one at a time.
 *
 * The engine swap is not proven by one film. The list is the testbed's own — id
 * and url per line, generated from its catalogue — so a fixture added there is a
 * fixture this probes, and the report prints the whole population beside the
 * verdict rather than a count of the ones that passed.
 *
 * Decode AND seek, because they fail separately: an HLS master that opens and
 * refuses to seek is a film a viewer cannot scrub, and a probe that only opened
 * would call it good. The plan's own note applies — a single-frame probe raced
 * the seek and reported NO VIDEO on five of twenty, so this waits for real
 * frames on both sides.
 */
class MpvFixtureProbeTest {

    private class FrameCounter : VideoFrameSink {
        var frames: Int = 0
        var lit: Int = 0

        override fun format(width: Int, height: Int): Unit = Unit

        override fun display(picture: ByteBuffer) {
            frames += 1
            var index = 0
            while (index < picture.limit()) {
                if (picture.get(index) != 0.toByte()) {
                    lit += 1
                    return
                }
                index += SAMPLE_STEP
            }
        }
    }

    private class Verdict(
        val id: String,
        val duration: Double,
        val framesBeforeSeek: Int,
        val litBeforeSeek: Int,
        val playheadAfterSeek: Double,
        val framesAfterSeek: Int,
    ) {
        val decoded: Boolean get() = litBeforeSeek > 0
        val seeked: Boolean get() = playheadAfterSeek > SEEK_TO - SEEK_TOLERANCE && framesAfterSeek > 0
        val ok: Boolean get() = decoded && seeked

        override fun toString(): String = buildString {
            append(if (ok) "PASS " else "FAIL ")
            append(id.padEnd(28))
            append("duration ${"%.1f".format(duration).padStart(8)}  ")
            append("frames ${framesBeforeSeek.toString().padStart(4)} (${litBeforeSeek} with picture)  ")
            append("after seek ${"%.1f".format(playheadAfterSeek).padStart(6)} +$framesAfterSeek")
        }
    }

    @Test
    fun everyTestbedFixtureDecodesAndSeeks() {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) return println("SKIPPED: libmpv unavailable — $reason")

        val listing: String? = System.getProperty("nomercy.mpv.fixtures")
        if (listing == null) return println("SKIPPED: no -Dnomercy.mpv.fixtures=<tsv of id and url>")

        val fixtures: List<Pair<String, String>> = File(listing).readLines()
            .filter { line -> line.isNotBlank() }
            .map { line -> line.substringBefore('\t') to line.substringAfter('\t') }

        val verdicts: List<Verdict> = fixtures.map { (id, url) -> probe(id, url) }
        verdicts.forEach(::println)

        val failed: List<Verdict> = verdicts.filter { verdict -> !verdict.ok }
        println("mpv fixture probe: ${verdicts.size - failed.size} of ${verdicts.size} decode and seek")
        assertTrue(failed.isEmpty(), "${failed.size} of ${verdicts.size} failed: ${failed.map { it.id }}")
    }

    private fun probe(id: String, url: String): Verdict = runBlocking {
        val engine = MpvVideoBackend()
        val sink = FrameCounter()
        try {
            engine.videoFrameSink(sink)
            engine.load(url, LoadOptions(autoplay = true))
            delay(OPEN_MS)

            val duration: Double = engine.duration()
            val framesBefore: Int = sink.frames
            val litBefore: Int = sink.lit

            engine.currentTime(SEEK_TO)
            delay(SEEK_MS)

            Verdict(id, duration, framesBefore, litBefore, engine.currentTime(), sink.frames - framesBefore)
        } finally {
            engine.release()
        }
    }

    private companion object {
        // Twenty seconds, not nine. Cosmos Laundromat's master carries three
        // audio renditions and eleven video ones and had not produced a frame
        // at nine — reported as FAIL on a film that decodes 1119 frames when
        // given the time. An open that is merely slow is not a defect, and a
        // probe that cannot tell them apart files one.
        val OPEN_MS: Long = System.getProperty("nomercy.mpv.openMs")?.toLong() ?: 20_000L
        const val SEEK_MS: Long = 6_000L

        // Far enough in to be past any title card and inside every fixture: the
        // shortest of them runs about ninety seconds.
        const val SEEK_TO: Double = 30.0
        const val SEEK_TOLERANCE: Double = 5.0
        const val SAMPLE_STEP: Int = 997
    }
}
