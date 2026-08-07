// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test

/**
 * The dispatch stack is written from more than one thread, and a pop that
 * assumes otherwise takes the whole event bus down.
 *
 * `dispatch` guarded its pop and the two before-paths did not, so the crash the
 * guard was written to stop still arrived through them:
 * `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 10`,
 * surfacing from `cycleAudioTrack` — a call that had nothing to do with it,
 * which is what made it look intermittent rather than structural.
 *
 * The stack backs `dispatching()`, a diagnostic read. An interleaved pop costs
 * a wrong name in a debug list; an unguarded one costs every listener in the
 * library.
 */
class ConcurrentDispatchStackTest {

    private object Probe {
        val ready: EventKey<BeforeEvent<Int>> = EventKey<BeforeEvent<Int>>("probe:ready")
        val tick: EventKey<Int> = EventKey<Int>("probe:tick")
    }

    @Test
    fun theStackSurvivesDispatchesFromSeveralThreads() = runTest {
        val emitter: EventEmitter<Any?> = EventEmitter()

        // Enough interleaving to make an unguarded pop certain rather than
        // lucky: the failure needs two dispatches to overlap, and one pair in
        // a few hundred is plenty.
        val rounds = 400

        withContext(Dispatchers.Default) {
            val work = (0 until rounds).map { round ->
                async<Unit> {
                    if (round % 2 == 0) {
                        emitter.dispatchBefore(Probe.ready, round)
                    } else {
                        emitter.emit(Probe.tick, round)
                    }
                }
            }

            // No assertion beyond "nothing threw". The defect is a crash, and a
            // test that survives it is the whole proof.
            work.awaitAll()
        }
    }
}
