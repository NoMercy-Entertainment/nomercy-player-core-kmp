// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// UnconfinedTestDispatcher is what makes a collector see an update the moment
// it happens instead of at the next scheduler tick, which is the whole point of
// the emission-count assertions below.
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStateHolderTest {

    @Test
    fun theSnapshotAndTheFlowNeverDisagree() {
        val holder = PlayerStateHolder()
        assertEquals(PlayerPhase.IDLE, holder.snapshot().phase)

        holder.update { it.copy(phase = PlayerPhase.PLAYING) }

        assertEquals(PlayerPhase.PLAYING, holder.snapshot().phase)
        assertEquals(holder.snapshot(), holder.stateFlow.value)
    }

    @Test
    fun aCollectorSeesTheCurrentStateThenEveryChange() = runTest {
        val holder = PlayerStateHolder()
        val seen = mutableListOf<PlayerPhase>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.stateFlow.collect { seen.add(it.phase) }
        }

        holder.update { it.copy(phase = PlayerPhase.PLAYING) }
        holder.update { it.copy(phase = PlayerPhase.PAUSED) }
        job.cancel()

        assertEquals(listOf(PlayerPhase.IDLE, PlayerPhase.PLAYING, PlayerPhase.PAUSED), seen)
    }

    @Test
    fun anUpdateThatChangesNothingEmitsNothing() = runTest {
        val holder = PlayerStateHolder()
        val seen = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.stateFlow.collect { seen.add(it.volume) }
        }

        holder.update { it.copy(volume = 100) }
        holder.update { it.copy(volume = 80) }
        holder.update { it.copy(volume = 80) }
        job.cancel()

        // A chrome recomposes on every emission, so a no-op write must not
        // produce one. This is what PlayerState being a data class buys.
        assertEquals(listOf(100, 80), seen)
    }

    @Test
    fun anInitialStateCanBeHandedIn() {
        val holder = PlayerStateHolder(PlayerState(volume = 40, phase = PlayerPhase.READY))

        assertEquals(40, holder.snapshot().volume)
        assertEquals(PlayerPhase.READY, holder.snapshot().phase)
    }
}
