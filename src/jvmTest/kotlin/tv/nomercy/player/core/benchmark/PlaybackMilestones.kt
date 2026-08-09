// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import java.util.concurrent.ConcurrentHashMap
import tv.nomercy.player.core.ports.BackendEvents
import tv.nomercy.player.core.ports.MediaBackend

/**
 * When each thing a viewer is waiting for first happened, measured from load.
 *
 * The milestones are the backend's own events rather than a poll of its state,
 * because the complaint about the desktop engine is TWO complaints — it is slow,
 * and its events are wrong — and polling would answer only the first while
 * quietly covering for the second. An event that never arrives is recorded as
 * never, and a stopwatch that reads "never" for `canplay` on a film that plays
 * is exactly the defect a duration measurement cannot see.
 *
 * Subscriptions are kept so they can be handed back on [detach]; a bus that
 * still holds a closure over a released engine is a leak the memory column would
 * then charge to the next fixture.
 */
internal class PlaybackMilestones(private val backend: MediaBackend) {

    private val firstAt: MutableMap<String, Long> = ConcurrentHashMap()
    private val counts: MutableMap<String, Int> = ConcurrentHashMap()
    private val listeners: MutableMap<String, (Any?) -> Unit> = LinkedHashMap()

    @Volatile private var startedAt: Long = System.nanoTime()

    /** The last message a stream error carried, which is why a fixture failed. */
    @Volatile var failure: String? = null
        private set

    fun attach(): PlaybackMilestones {
        WATCHED.forEach { event ->
            val listener: (Any?) -> Unit = { payload -> record(event, payload) }
            listeners[event] = listener
            backend.on(event, listener)
        }
        return this
    }

    fun detach() {
        listeners.forEach { (event, listener) -> backend.off(event, listener) }
        listeners.clear()
    }

    /** Restarts the stopwatch without dropping the subscriptions, for the seek leg. */
    fun restart() {
        startedAt = System.nanoTime()
        firstAt.clear()
        counts.clear()
    }

    /** Milliseconds from the stopwatch to [event], or null when it never fired. */
    fun at(event: String): Long? = firstAt[event]

    fun count(event: String): Int = counts[event] ?: 0

    /**
     * Every event a healthy playback owes and did not deliver.
     *
     * [BackendEvents.WAITING] and [BackendEvents.STREAM_ERROR] are absent from
     * that list on purpose: a film that never stalled and never failed is the
     * good outcome, and reporting the two of them as missing put "never:
     * waiting" beside every fixture that played perfectly.
     */
    fun missing(): List<String> = WATCHED.filter { event -> firstAt[event] == null } - OPTIONAL

    private fun record(event: String, payload: Any?) {
        counts.merge(event, 1, Int::plus)
        firstAt.putIfAbsent(event, sinceStart())
        if (event == BackendEvents.STREAM_ERROR) failure = payload?.toString() ?: "stream error"
    }

    private fun sinceStart(): Long = (System.nanoTime() - startedAt) / NANOS_PER_MS

    private companion object {
        // Ordered the way a load runs, so a report reading left to right reads
        // as the sequence a viewer experiences.
        val WATCHED: List<String> = listOf(
            BackendEvents.LOAD_START,
            BackendEvents.LOADED_METADATA,
            BackendEvents.CAN_PLAY,
            BackendEvents.PLAY,
            BackendEvents.PLAYING,
            BackendEvents.TIME_UPDATE,
            BackendEvents.WAITING,
            BackendEvents.STREAM_ERROR,
        )

        // Watched so their timing is recorded, never owed: both mean something
        // went wrong, and their absence is the outcome being hoped for.
        val OPTIONAL: Set<String> = setOf(BackendEvents.WAITING, BackendEvents.STREAM_ERROR)

        const val NANOS_PER_MS: Long = 1_000_000
    }
}
