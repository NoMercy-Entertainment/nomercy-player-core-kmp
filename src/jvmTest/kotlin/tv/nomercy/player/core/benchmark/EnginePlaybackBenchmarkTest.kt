// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.ports.engines.VideoEngineProvider
import tv.nomercy.player.core.ports.engines.VideoEngines

/**
 * Every registered desktop engine, over every testbed fixture, side by side.
 *
 * This exists because an engine was replaced on a gate that answered yes or no.
 * Twenty of twenty fixtures decoded and seeked, which was true and was also
 * compatible with every one of them taking six seconds to show a picture — so
 * the report said the migration was done while the thing on screen felt worse
 * than what it replaced. A boolean cannot be argued with and cannot be compared
 * either.
 *
 * It reports rather than asserts. A threshold picked here would be a number
 * invented by whoever wrote the file, and the question this answers is not "is
 * it fast enough" but "which of these two is faster on this machine" — which is
 * a table someone reads, not a tick. MpvFixtureProbeTest is the wall.
 */
class EnginePlaybackBenchmarkTest {

    @Test
    fun everyEngineOverEveryFixture() {
        val fixtures: List<BenchmarkFixture>? = BenchmarkFixtures.fromProperty()
        if (fixtures == null) return println("SKIPPED: no -D${BenchmarkFixtures.PROPERTY}=<tsv of id and url>")

        val chosen: List<BenchmarkFixture> = BenchmarkFixtures.narrow(fixtures, System.getProperty(ONLY))
        if (chosen.isEmpty()) return println("SKIPPED: no fixture matched -D$ONLY")

        val engines: List<VideoEngineProvider> = VideoEngines.registered.filter { engine ->
            engine.isAvailable().also { available ->
                if (!available) println("engine ${engine.id}: unavailable here — ${engine.whyUnavailable()}")
            }
        }
        if (engines.isEmpty()) return println("SKIPPED: no registered engine is available here")

        engines.forEach { engine -> report(engine, chosen) }
    }

    private fun report(engine: VideoEngineProvider, fixtures: List<BenchmarkFixture>) {
        val run = EngineBenchmarkRun(
            engine,
            openBudgetMs = budget(OPEN_MS, OPEN_DEFAULT),
            seekBudgetMs = budget(SEEK_MS, SEEK_DEFAULT),
        )

        val results: List<EngineBenchmarkResult> = runBlocking {
            fixtures.map { fixture -> run.measure(fixture.id, fixture.url) }
        }

        println()
        println("engine ${engine.id} — ${fixtures.size} fixtures, milliseconds")
        println(EngineBenchmarkResult.header())
        results.forEach { result -> println(result.row()) }
        println(summarise(engine.id, results))
    }

    // Median rather than mean, because one fixture that never opens would
    // otherwise drag the headline number and hide where the rest of them sit.
    private fun summarise(engineId: String, results: List<EngineBenchmarkResult>): String {
        val playable: List<EngineBenchmarkResult> = results.filter(EngineBenchmarkResult::playable)
        val pictures: List<Long> = playable.mapNotNull(EngineBenchmarkResult::firstPictureMs).sorted()
        val seeks: List<Long> = playable.mapNotNull(EngineBenchmarkResult::seekMs).sorted()

        val worst: String = pictures.lastOrNull()?.toString() ?: "-"
        val peak: Long = results.maxOfOrNull(EngineBenchmarkResult::memoryMb) ?: 0

        return "$engineId: ${playable.size} of ${results.size} playable, " +
            "median picture ${median(pictures)}, median seek ${median(seeks)}, " +
            "worst picture $worst, rss ${peak}M"
    }

    private fun median(sorted: List<Long>): String = if (sorted.isEmpty()) "-" else "${sorted[sorted.size / 2]}"

    private fun budget(property: String, fallback: Long): Long =
        System.getProperty(property)?.toLongOrNull() ?: fallback

    private companion object {
        const val ONLY: String = "nomercy.benchmark.only"
        const val OPEN_MS: String = "nomercy.benchmark.openMs"
        const val SEEK_MS: String = "nomercy.benchmark.seekMs"

        // A ceiling the slowest fixture has cleared, not a wait. Cosmos
        // Laundromat's master carries eleven video renditions and three audio
        // ones and is the one that has ever needed the room.
        const val OPEN_DEFAULT: Long = 30_000
        const val SEEK_DEFAULT: Long = 15_000
    }
}
