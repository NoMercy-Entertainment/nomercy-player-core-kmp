// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

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
) {
    private val buffer: ArrayDeque<InspectorEvent> = ArrayDeque()
    private val mutable: MutableStateFlow<List<InspectorEvent>> = MutableStateFlow(emptyList())
    private val startedAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    private var sequence: Long = 0

    // The live stream, newest last, for anything that renders.
    public val events: StateFlow<List<InspectorEvent>> = mutable.asStateFlow()

    private val subscription: Subscription = player.onAll { name, payload -> record(name, payload) }

    // How many of each, over what is still in the buffer.
    //
    // Folded on demand rather than counted as they arrive: a running tally
    // would keep counting events the buffer has already dropped, so the numbers
    // and the lines under them would describe different windows.
    public fun counts(): Map<String, Int> =
        buffer.groupingBy { it.name }.eachCount()

    public fun clear() {
        buffer.clear()
        mutable.value = emptyList()
    }

    // Stops recording. A debug tool nobody can switch off is a debug tool that
    // ships, and the subscription is the only thing here holding a reference to
    // the player.
    public fun dispose() {
        subscription.dispose()
        clear()
    }

    private fun record(name: String, payload: Any?) {
        // removeAt(0), not removeFirst(). On JVM target 21 Kotlin resolves
        // removeFirst() to java.util.SequencedCollection's, which does not
        // exist on every Android runtime this library supports.
        if (buffer.size >= capacity) buffer.removeAt(0)
        buffer.addLast(
            InspectorEvent(
                sequence = sequence,
                atMs = startedAt.elapsedNow().inWholeMilliseconds,
                name = name,
                summary = summarize(payload),
            ),
        )
        sequence += 1
        mutable.value = buffer.toList()
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
    }
}
