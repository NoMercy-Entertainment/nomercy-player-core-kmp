// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.testing.FakePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerInspectorTest {

    // The stream, in the order it happened. An inspector reading a recording of
    // the events would pass a test that only checked the set of names, and be
    // useless for the one question it exists to answer: what happened before
    // the thing that went wrong.
    @Test
    fun theInspectorRecordsTheLiveStreamInOrder() = runTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)

        player.emit(CoreEvents.Play, PlaySource("user"))
        player.emit(CoreEvents.Playing, Unit)
        player.emit(CoreEvents.Pause, PlaySource("user"))

        assertEquals(
            listOf("play", "playing", "pause"),
            inspector.events.value.map { it.name },
        )
    }

    // Sequence numbers, not positions. Once the buffer has dropped its oldest
    // entries the index in the list stops meaning anything, and "did I miss
    // one" is exactly the question somebody reading a debug stream is asking.
    @Test
    fun theOldestEventsAreDroppedAndTheSequenceKeepsCounting() = runTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player, capacity = 3)
        val ping: EventKey<Int> = EventKey("ping")

        repeat(10) { player.emit(ping, it) }

        val recorded: List<InspectorEvent> = inspector.events.value
        assertEquals(3, recorded.size)
        assertEquals(listOf(7L, 8L, 9L), recorded.map { it.sequence })
        assertEquals(listOf("7", "8", "9"), recorded.map { it.summary })
    }

    @Test
    fun countsFoldTheBufferByName() = runTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)

        player.emit(CoreEvents.Play, PlaySource("user"))
        player.emit(CoreEvents.Play, PlaySource("remote"))
        player.emit(CoreEvents.Pause, PlaySource("user"))

        assertEquals(mapOf("play" to 2, "pause" to 1), inspector.counts())
    }

    @Test
    fun clearEmptiesTheBufferAndKeepsRecording() = runTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)
        player.emit(CoreEvents.Play, PlaySource("user"))

        inspector.clear()
        assertTrue(inspector.events.value.isEmpty())

        player.emit(CoreEvents.Pause, PlaySource("user"))
        assertEquals(listOf("pause"), inspector.events.value.map { it.name })
    }

    // A debug tool nobody can switch off is a debug tool that ships to
    // production. The subscription goes when the inspector does.
    @Test
    fun disposeUnsubscribesFromTheFirehose() = runTest {
        val player = FakePlayer()
        val before: Int = player.listenerCount()
        val inspector = PlayerInspector(player)

        inspector.dispose()
        player.emit(CoreEvents.Play, PlaySource("user"))

        assertEquals(before, player.listenerCount())
        assertTrue(inspector.events.value.isEmpty())
    }

    // A payload that throws from toString is a payload a debug tool must not
    // crash the app over. It happens: a lazily-computed field that is not ready
    // yet, a proxy that talks to a service.
    @Test
    fun aPayloadThatCannotDescribeItselfDoesNotTakeTheAppDown() = runTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)
        val hostile: EventKey<Any> = EventKey("hostile")

        player.emit(hostile, UndescribableThing())

        assertEquals(listOf("hostile"), inspector.events.value.map { it.name })
        assertTrue(
            inspector.events.value.single().summary.contains("unprintable"),
            "the summary hid the failure: ${inspector.events.value.single().summary}",
        )
    }

    // `time` fires several times a second, so on a default buffer it evicts the
    // run-up to a failure inside a few seconds — the exact window this tool
    // exists to hold.
    @Test
    fun mutedNamesAreNotKept() {
        val player = FakePlayer()
        val inspector = PlayerInspector(player, muted = setOf("time"))
        val time: EventKey<Any> = EventKey("time")
        val failed: EventKey<Any> = EventKey("error")

        repeat(50) { player.emit(time, it) }
        player.emit(failed, "boom")

        assertEquals(listOf("error"), inspector.events.value.map { it.name })
    }

    // Opt-in rather than the default: a debug tool that silently drops events
    // answers "is time arriving" with a confident no.
    @Test
    fun nothingIsMutedUnlessAsked() {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)

        player.emit(EventKey<Any>("time"), 1)

        assertEquals(listOf("time"), inspector.events.value.map { it.name })
    }

    @Test
    fun mutingLaterLeavesWhatWasAlreadyRead() {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)
        val time: EventKey<Any> = EventKey("time")

        player.emit(time, 1)
        inspector.mute(setOf("time"))
        player.emit(time, 2)

        // The first line stays: dropping it would erase the history somebody
        // muted in order to read.
        assertEquals(1, inspector.events.value.size)
        assertEquals(setOf("time"), inspector.muted())
    }

    @Test
    fun unmutingLetsThemThroughAgain() {
        val player = FakePlayer()
        val inspector = PlayerInspector(player, muted = PlayerInspector.NOISY_EVENT_NAMES)
        val time: EventKey<Any> = EventKey("time")

        player.emit(time, 1)
        inspector.unmute(setOf("time"))
        player.emit(time, 2)

        assertEquals(1, inspector.events.value.size)
    }
}
// A toString that throws is the thing under test, so both rules that object to
// one are off here and nowhere else. The payloads that do this in the wild are
// not written on purpose either: a field computed on first read that is not
// ready yet, or a handle to something already closed.
@Suppress("ExceptionRaisedInUnexpectedLocation", "UseCheckOrError")
private class UndescribableThing {
    override fun toString(): String = throw IllegalStateException("not ready")
}
