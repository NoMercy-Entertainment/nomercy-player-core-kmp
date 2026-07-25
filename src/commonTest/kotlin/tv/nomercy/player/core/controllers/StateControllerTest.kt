// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState
import tv.nomercy.player.core.player.VolumeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StateControllerTest {

    private class StateRig {
        val ctx: PlayerContext = newContext()
        val queue: QueueController = QueueController(ctx)
        val transport: TransportController = TransportController(ctx, queue)
        val time: TimeController = TimeController(ctx, queue, transport)
        val volume: VolumeController = VolumeController(ctx)
        val state: StateController = StateController(ctx, queue, time)

        init {
            queue.transport = transport
            queue.wireQueue()
        }

        fun ready(): StateRig = apply { ctx.transitionPhase(tv.nomercy.player.core.player.PlayerPhase.READY) }
    }

    @Test
    fun theSnapshotIsBuiltFromTheContextRatherThanAParallelCopy() = runTest {
        val rig = StateRig().ready()
        rig.queue.queue(items("a", "b"))

        rig.transport.play()

        val snapshot = rig.state.state()
        assertEquals(PlayState.PLAYING, snapshot.playState)
        assertEquals("a", snapshot.item?.id)
        assertEquals(0, snapshot.index)
        assertEquals(2, snapshot.queueLength)
        assertEquals(100, snapshot.volume)
    }

    @Test
    fun theFlowTracksPlaybackWithoutAnyonePushingToIt() = runTest {
        val rig = StateRig().ready()
        rig.queue.queue(items("a"))

        rig.transport.play()

        assertEquals(PlayState.PLAYING, rig.state.stateFlow.value.playState)
    }

    @Test
    fun theFlowTracksAVolumeChangeMadeThroughADifferentController() = runTest {
        val rig = StateRig().ready()

        rig.volume.volume(40)

        // Both controllers write the same context, so the snapshot cannot
        // disagree with the player it came from.
        assertEquals(40, rig.state.stateFlow.value.volume)
    }

    @Test
    fun aMutedPlayerReportsZeroWhileRememberingItsLevel() = runTest {
        val rig = StateRig().ready()
        rig.volume.volume(60)

        rig.volume.mute()

        assertEquals(0, rig.state.stateFlow.value.volume)
        assertTrue(rig.state.stateFlow.value.muted)
        assertEquals(VolumeState.MUTED, rig.state.volumeState())

        rig.volume.unmute()
        assertEquals(60, rig.state.stateFlow.value.volume)
    }

    @Test
    fun theSnapshotIsAValueSoTwoReadsOfAnUnchangedPlayerCompareEqual() = runTest {
        val rig = StateRig().ready()

        val first = rig.state.state()
        val second = rig.state.state()
        assertEquals(first, second)

        rig.volume.volume(10)
        assertNotEquals(first, rig.state.state())
    }

    @Test
    fun settingRepeatAnnouncesItAndSettingItAgainDoesNot() = runTest {
        val rig = StateRig()
        var announced = 0
        var last: RepeatState? = null
        rig.ctx.on(CoreEvents.Repeat) { announced += 1; last = it.state }

        rig.state.repeatState(RepeatState.ALL)
        rig.state.repeatState(RepeatState.ALL)

        assertEquals(RepeatState.ALL, rig.ctx.repeatState)
        assertEquals(RepeatState.ALL, last)
        assertEquals(1, announced)
    }

    @Test
    fun aRefusedRepeatChangeLeavesTheModeAlone() = runTest {
        val rig = StateRig()
        rig.ctx.on(CoreEvents.BeforeRepeat) { it.preventDefault() }
        var prevented = 0
        rig.ctx.on(CoreEvents.RepeatPrevented) { prevented += 1 }

        rig.state.repeatState(RepeatState.ONE)

        assertEquals(1, prevented)
        assertEquals(RepeatState.OFF, rig.ctx.repeatState)
    }

    @Test
    fun turningShuffleOnReordersWhatIsLeftAndAnnouncesIt() = runTest {
        val rig = StateRig()
        rig.queue.queue(items("a", "b", "c", "d"))
        var announced: ShuffleState? = null
        rig.ctx.on(CoreEvents.Shuffle) { announced = it.state }

        rig.state.shuffleState(true)

        assertEquals(ShuffleState.ON, rig.ctx.shuffleState)
        assertEquals(ShuffleState.ON, announced)
        assertEquals(4, rig.queue.queueLength())
    }

    @Test
    fun aRefusedShuffleLeavesBothTheModeAndTheOrderAlone() = runTest {
        val rig = StateRig()
        rig.queue.queue(items("a", "b", "c"))
        val before = rig.queue.queue().map { it.id }
        rig.ctx.on(CoreEvents.BeforeShuffle) { it.preventDefault() }
        var prevented = 0
        rig.ctx.on(CoreEvents.ShufflePrevented) { prevented += 1 }

        rig.state.shuffleState(ShuffleState.ON)

        assertEquals(1, prevented)
        assertEquals(ShuffleState.OFF, rig.ctx.shuffleState)
        assertEquals(before, rig.queue.queue().map { it.id })
    }

    @Test
    fun turningShuffleOffLeavesTheOrderWhereItIs() = runTest {
        val rig = StateRig()
        rig.queue.queue(items("a", "b", "c"))
        rig.state.shuffleState(ShuffleState.ON)
        val shuffled = rig.queue.queue().map { it.id }

        rig.state.shuffleState(ShuffleState.OFF)

        // Unshuffling into the original order would move the item the viewer
        // is on out from under them.
        assertEquals(shuffled, rig.queue.queue().map { it.id })
        assertEquals(ShuffleState.OFF, rig.state.shuffleState())
    }

    @Test
    fun theReadAccessorsReportWhatTheContextHolds() {
        val rig = StateRig()
        rig.ctx.playState = PlayState.PAUSED

        assertEquals(PlayState.PAUSED, rig.state.playState())
        assertEquals(RepeatState.OFF, rig.state.repeatState())
        assertEquals(ShuffleState.OFF, rig.state.shuffleState())
    }
}
