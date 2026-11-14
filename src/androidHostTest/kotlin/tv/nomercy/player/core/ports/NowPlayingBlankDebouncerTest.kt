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

class NowPlayingBlankDebouncerTest {
    private class ManualScheduler : UpdateScheduler {
        private data class Scheduled(val atMs: Long, val task: () -> Unit, var cancelled: Boolean = false)

        var nowMs = 0L
            private set
        private val tasks = mutableListOf<Scheduled>()

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val scheduled = Scheduled(nowMs + delayMs, task)
            tasks += scheduled
            return { scheduled.canceled = true }
        }

        fun advanceBy(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.filter { !it.canceled && it.atMs <= target }
                    .minByOrNull { it.atMs } ?: break
                tasks.remove(next)
                nowMs = next.atMs
                next.task()
            }
            nowMs = target
        }
    }

    private val clock = ManualScheduler()
    private val published = mutableListOf<String?>()
    private val debouncer = NowPlayingBlankDebouncer<String>(
        delayMs = WINDOW_MS,
        scheduler = clock,
        publish = { item: String -> published += item },
        blank = { published += null },
    )

    @Test
    fun replacementInsideWindowCancelsBlankWithoutAnEmptyPublication() {
        debouncer.setNowPlaying("Playground")
        debouncer.clearNowPlaying()
        assertEquals(listOf<String?>("Playground"), published, "clear must keep the last item during the window")

        clock.advanceBy(WINDOW_MS - 1)
        debouncer.setNowPlaying("Our Love")
        clock.advanceBy(WINDOW_MS * 2)

        assertEquals(listOf<String?>("Playground", "Our Love"), published)
    }

    @Test
    fun finalClearPublishesOneBlankOnlyAfterTheWindow() {
        debouncer.setNowPlaying("Playground")
        debouncer.clearNowPlaying()
        clock.advanceBy(WINDOW_MS - 1)
        assertEquals(listOf<String?>("Playground"), published)

        clock.advanceBy(1)
        clock.advanceBy(WINDOW_MS * 2)

        assertEquals(listOf<String?>("Playground", null), published)
    }

    @Test
    fun repeatedClearRestartsTheWindowAndStillBlanksOnlyOnce() {
        debouncer.setNowPlaying("Playground")
        debouncer.clearNowPlaying()
        clock.advanceBy(WINDOW_MS - 1)
        debouncer.clearNowPlaying()
        clock.advanceBy(WINDOW_MS - 1)
        assertEquals(listOf<String?>("Playground"), published)

        clock.advanceBy(1)
        assertEquals(listOf<String?>("Playground", null), published)
    }

    private companion object {
        const val WINDOW_MS = 3_000L
    }
}
