// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

import tv.nomercy.player.core.events.Subscription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

// Two threads recording at once must not hand out one sequence twice.
//
// The desktop player crashed on this in front of the boss: libVLC raises its
// events from its own delivery thread while the chrome records from the main
// one, both reached PlayerInspector.record, and `sequence` was read, shipped and
// then incremented as three separate steps. Two events left carrying 5, the
// inspector overlay keys its LazyColumn by sequence, and Compose took the window
// down with "Key 5 was already used".
//
// JVM-only because it needs real threads. The defect is in common code and the
// lock that fixes it is common too, but a test that cannot run two things at
// once cannot see this, and a single-threaded version would pass on the broken
// code — which is the whole reason it survived.
class PlayerInspectorConcurrencyTest {

    @Test
    fun everyRecordedEventGetsItsOwnSequence() {
        // The firehose the player would be, held so the test can raise events
        // from several threads the way libVLC's delivery thread and the chrome
        // do.
        var deliver: ((String, Any?) -> Unit)? = null
        val firehose = EventFirehose { fn ->
            deliver = fn
            Subscription { deliver = null }
        }

        val inspector = PlayerInspector(firehose, capacity = TOTAL * 2)
        val raise: (String, Any?) -> Unit = requireNotNull(deliver) { "the inspector did not subscribe" }

        val pool = Executors.newFixedThreadPool(THREADS)
        val start = CountDownLatch(1)
        val done = CountDownLatch(THREADS)

        repeat(THREADS) { thread ->
            pool.execute {
                start.await()
                repeat(PER_THREAD) { index -> raise("e-$thread-$index", null) }
                done.countDown()
            }
        }

        start.countDown()
        check(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "recording threads did not finish" }
        pool.shutdown()

        val sequences: List<Long> = inspector.events.value.map { it.sequence }

        // Both halves matter. Distinct catches the duplicate the overlay chokes
        // on; the count catches an event lost to the unguarded ArrayDeque, which
        // is the same race arriving as silence instead of a crash.
        assertEquals(TOTAL, sequences.size, "an event went missing")
        assertEquals(TOTAL, sequences.distinct().size, "a sequence was handed out twice")
    }

    private companion object {
        const val THREADS = 8
        const val PER_THREAD = 500
        const val TOTAL = THREADS * PER_THREAD
        const val TIMEOUT_SECONDS = 30L
    }
}
