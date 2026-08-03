// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals

// The four the backlog announces, none of which any test had ever named.
//
// The backlog shares MediaList with the queue and the wiring picks the event
// pair by a boolean, so a consumer restoring a session listens for `backlog`
// where the queue's own listener hears `queue`. A wiring that emitted the queue
// key for both would look correct from inside - the list changed, an event
// fired - and every backlog listener in every consumer would be silent.
class BacklogEventsTest {

    private fun player(): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend()).also {
            it.queue(listOf(TestItem("a"), TestItem("b")))
        }

    private fun ComposedPlayer.record(): MutableList<String> {
        val seen: MutableList<String> = mutableListOf()

        on(CoreEvents.Backlog) { seen += "backlog" }
        on(CoreEvents.BacklogAppend) { seen += "append" }
        on(CoreEvents.BacklogRemove) { seen += "remove" }
        on(CoreEvents.BacklogClear) { seen += "clear" }
        // The queue's own four, so a wiring that fired the wrong pair is caught
        // rather than merely missed.
        on(CoreEvents.Queue) { seen += "QUEUE" }
        on(CoreEvents.QueueAppend) { seen += "QUEUE-append" }
        on(CoreEvents.QueueRemove) { seen += "QUEUE-remove" }
        on(CoreEvents.QueueClear) { seen += "QUEUE-clear" }

        return seen
    }

    @Test
    fun settingTheBacklogAnnouncesItAsTheBacklog() {
        val subject: ComposedPlayer = player()
        val seen: MutableList<String> = subject.record()

        subject.backlog(listOf(TestItem("x")))

        assertEquals(listOf("backlog"), seen)
    }

    // The specific event first, then the change.
    //
    // MediaList emits the detail - what was appended, what was removed - and
    // calls emitChange() last, so a listener that only wants "the list is now
    // this" hears it after the one that wants to know what happened. Asserted
    // rather than assumed: the order is the contract a consumer writes against,
    // and my first expectation had it backwards.
    @Test
    fun appendingAnnouncesBothTheAppendAndTheChange() {
        val subject: ComposedPlayer = player()
        val seen: MutableList<String> = subject.record()

        subject.backlogAppend(listOf(TestItem("x")))

        assertEquals(listOf("append", "backlog"), seen)
    }

    @Test
    fun removingNamesTheBacklogRatherThanTheQueue() {
        val subject: ComposedPlayer = player()
        subject.backlog(listOf(TestItem("x")))
        val seen: MutableList<String> = subject.record()

        subject.backlogRemove("x")

        assertEquals(listOf("remove", "backlog"), seen)
    }

    @Test
    fun andClearingDoesToo() {
        val subject: ComposedPlayer = player()
        subject.backlog(listOf(TestItem("x")))
        val seen: MutableList<String> = subject.record()

        subject.backlogClear()

        assertEquals(listOf("clear", "backlog"), seen)
    }
}
