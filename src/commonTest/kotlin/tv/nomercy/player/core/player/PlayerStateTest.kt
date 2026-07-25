// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import tv.nomercy.player.core.media.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private data class FakeItem(override val id: String) : PlaylistItem

class PlayerStateTest {

    @Test
    fun theDefaultSnapshotIsAPlayerThatHasNotStarted() {
        val state = PlayerState()

        assertEquals(PlayerPhase.IDLE, state.phase)
        assertEquals(PlayState.IDLE, state.playState)
        assertEquals(SetupState.NOT_SETUP, state.setupState)
        assertEquals(100, state.volume)
        assertEquals(1.0, state.playbackRate)
        assertEquals(0, state.queueLength)
        assertNull(state.item)
    }

    @Test
    fun theDefaultIndexIsMinusOneSoNoItemLooksCurrent() {
        // 0 would claim the first item is playing before anything loaded.
        assertEquals(-1, PlayerState().index)
    }

    @Test
    fun theSnapshotCarriesEveryFieldItWasGiven() {
        val item = FakeItem("track-1")
        val state = PlayerState(
            phase = PlayerPhase.PLAYING,
            playState = PlayState.PLAYING,
            setupState = SetupState.READY,
            time = 12.5,
            duration = 200.0,
            buffered = 30.0,
            volume = 80,
            muted = true,
            volumeState = VolumeState.MUTED,
            playbackRate = 1.5,
            item = item,
            index = 0,
            queueLength = 3,
            repeatState = RepeatState.ALL,
            shuffleState = ShuffleState.ON,
            bufferState = BufferState.LOADING,
            networkState = NetworkState.SLOW,
            castState = CastState.CONNECTED,
        )

        assertEquals(12.5, state.time)
        assertEquals("track-1", state.item?.id)
        assertEquals(RepeatState.ALL, state.repeatState)
        assertEquals(CastState.CONNECTED, state.castState)
        assertEquals(VolumeState.MUTED, state.volumeState)
    }

    @Test
    fun equalityIsStructuralSoConflationCanWork() {
        // StateFlow drops an emission when the new value equals the old one.
        // That only means anything if two identical snapshots compare equal.
        assertEquals(PlayerState(), PlayerState())
        assertNotEquals(PlayerState(), PlayerState(volume = 80))
    }

    @Test
    fun copyLeavesTheOriginalUntouched() {
        val idle = PlayerState()

        val playing = idle.copy(phase = PlayerPhase.PLAYING, playState = PlayState.PLAYING)

        assertEquals(PlayerPhase.IDLE, idle.phase)
        assertEquals(PlayerPhase.PLAYING, playing.phase)
        assertEquals(idle.volume, playing.volume)
    }
}
