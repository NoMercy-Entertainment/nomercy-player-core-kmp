// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nomercy.player.core.events.Subscription
import kotlin.time.TimeSource

// The firehose, elevated to a tool.
//
// Every consumer debugging this player writes the same three lines — subscribe
// to everything, print it, wonder why the last twenty are gone by the time they
// look. This keeps them, bounded, in the order they arrived, in something a
// view can render.
//
// It is the answer to "what happened just before the thing that went wrong",
// which is a question a breakpoint cannot answer: by the time the failure is
// visible, the events that caused it have been dispatched and forgotten.
//
// Reflection-free on purpose. Kotlin/Native has none, so a summariser that
// walked the payload's fields would work on Android and the desktop and produce
// nothing on the two platforms where attaching a debugger is hardest.
public class PlayerInspector(
    player: EventFirehose,
    private val capacity: Int = DEFAULT_CAPACITY,
    muted: Set<String> = emptySet(),
) {
    private val buffer: ArrayDeque<InspectorEvent> = ArrayDeque()
    private val mutable: MutableStateFlow<List<InspectorEvent>> = MutableStateFlow(emptyList())
    private val startedAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    private val lock: SynchronizedObject = SynchronizedObject()

    private var sequence: Long = 0

    // Names that are recorded but not kept.
    //
    // `time` fires several times a second, so on a default buffer it evicts the
    // run-up to a failure inside a few seconds — the exact window this tool
    // exists to hold. Muting is opt-in rather than the default: a debug tool
    // that silently drops events answers "is time arriving" with a confident no.
    // [NOISY_EVENT_NAMES] is the set most callers want, named so opting in is
    // one symbol instead of a hand-written list that drifts.
    private val muted: MutableSet<String> = muted.toMutableSet()

    // The live stream, newest last, for anything that renders.
    public val events: StateFlow<List<InspectorEvent>> = mutable.asStateFlow()

    private val subscription: Subscription = player.onAll { name, payload -> record(name, payload) }

    // How many of each, over what is still in the buffer.
    //
    // Folded on demand rather than counted as they arrive: a running tally
    // would keep counting events the buffer has already dropped, so the numbers
    // and the lines under them would describe different windows.
    public fun counts(): Map<String, Int> =
        synchronized(lock) { buffer.groupingBy { it.name }.eachCount() }

    /** Which names are currently dropped. */
    public fun muted(): Set<String> = muted.toSet()

    /**
     * Stop keeping these. Already-buffered lines stay: dropping them would
     * erase the history somebody muted in order to read.
     */
    public fun mute(names: Set<String>) {
        muted += names
    }

    public fun unmute(names: Set<String>) {
        muted -= names
    }

    public fun clear() {
        synchronized(lock) {
            buffer.clear()
            mutable.value = emptyList()
        }
    }

    // Stops recording. A debug tool nobody can switch off is a debug tool that
    // ships, and the subscription is the only thing here holding a reference to
    // the player.
    public fun dispose() {
        subscription.dispose()
        clear()
    }

    // Under a lock, because events no longer arrive on one thread.
    //
    // `sequence` is read, written into an event, and then incremented — three
    // steps that are not one. Two threads recording at once both read the same
    // value and both ship it, and a consumer keying a list by sequence gets
    // "Key 5 was already used" and takes the window down. The desktop player is
    // where this became reachable: libVLC raises its events from its own
    // delivery thread while the chrome records from the main one, so the two
    // meet here.
    //
    // The buffer is the other half. ArrayDeque is not thread-safe and this
    // removes from the head and appends to the tail, so an unguarded pair of
    // callers can corrupt it in ways that surface far from here.
    private fun record(name: String, payload: Any?) {
        if (name in muted) return

        val event = InspectorEvent(
            sequence = 0,
            atMs = startedAt.elapsedNow().inWholeMilliseconds,
            name = name,
            summary = summarize(payload),
        )

        synchronized(lock) {
            // removeAt(0), not removeFirst(). On JVM target 21 Kotlin resolves
            // removeFirst() to java.util.SequencedCollection's, which does not
            // exist on every Android runtime this library supports.
            if (buffer.size >= capacity) buffer.removeAt(0)
            buffer.addLast(event.copy(sequence = sequence))
            sequence += 1
            mutable.value = buffer.toList()
        }
    }

    // A payload whose toString throws is a payload a debug tool must not crash
    // the app over. It happens — a lazily-computed field that is not ready, a
    // handle to something already closed — and the one place it happens is
    // while something is already going wrong, which is the worst possible
    // moment to add a second failure.
    @Suppress("TooGenericExceptionCaught")
    private fun summarize(payload: Any?): String = when (payload) {
        null -> ""
        Unit -> ""
        else -> try {
            payload.toString().take(SUMMARY_LIMIT)
        } catch (failed: Throwable) {
            "<unprintable: ${failed::class.simpleName}>"
        }
    }

    public companion object {
        // Enough to hold the run-up to a failure, small enough that a consumer
        // leaving one attached is not a memory decision they have to think
        // about.
        public const val DEFAULT_CAPACITY: Int = 500

        // A line, not a document. A payload that renders longer than this is
        // one somebody should be reading in a debugger, and letting it through
        // makes every other line in the stream harder to scan.
        public const val SUMMARY_LIMIT: Int = 200

        // The high-frequency ones. Every consumer who has ever attached this
        // has muted the same three by hand, and a hand-written list drifts the
        // moment the player emits a fourth.
        // playback:metrics belongs here for the same reason the other three do
        // and was left out: it is a SAMPLER, so it arrives on a timer whatever
        // the player is doing. On a quiet film it is the only thing in the
        // buffer, and a log showing nothing but the same snapshot on repeat is
        // the instrument telling you nothing while looking like it works.
        public val NOISY_EVENT_NAMES: Set<String> =
            setOf("time", "progress", "buffered", "playback:metrics")
    }
}
