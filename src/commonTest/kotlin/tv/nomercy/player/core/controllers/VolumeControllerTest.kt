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
import tv.nomercy.player.core.player.VolumeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeControllerTest {

    @Test
    fun theReportedVolumeIsZeroWhileMutedWhateverIsStored() = runTest {
        val rig = Rig()
        assertEquals(100, rig.volume.volume())

        rig.volume.mute()

        // A slider bound to this shows what is being heard.
        assertEquals(0, rig.volume.volume())
        assertEquals(100, rig.ctx.volumeBeforeMute)
    }

    @Test
    fun settingTheVolumeClampsAnnouncesAndConvertsForTheEngine() = runTest {
        val rig = Rig()
        val levels = EventLog().capture(rig.ctx, CoreEvents.Volume)

        rig.volume.volume(150)

        assertEquals(100, rig.ctx.internalVolume)
        assertEquals(100, levels.last().level)
        // Public scale is 0..100, the engine's is 0..1, and the division
        // happens once here.
        assertEquals(1.0f, rig.backend.volumesSet.last())

        rig.volume.volume(50)
        assertEquals(0.5f, rig.backend.volumesSet.last())
    }

    @Test
    fun aVetoedVolumeChangeNeverReachesTheEngine() = runTest {
        val rig = Rig()
        rig.ctx.on(CoreEvents.BeforeVolume) { it.preventDefault() }
        val prevented = EventLog().capture(rig.ctx, CoreEvents.VolumePrevented)

        rig.volume.volume(30)

        assertEquals(1, prevented.size)
        assertEquals(100, rig.ctx.internalVolume)
        assertEquals(0, rig.backend.volumesSet.size)
    }

    @Test
    fun aVolumeListenerCanRedirectTheLevel() = runTest {
        val rig = Rig()
        rig.ctx.on(CoreEvents.BeforeVolume) { it.data = it.data.copy(level = 25) }

        rig.volume.volume(80)

        assertEquals(25, rig.ctx.internalVolume)
    }

    @Test
    fun mutingAnnouncesItselfAndReachesTheEngine() = runTest {
        val rig = Rig()
        val muted = EventLog().capture(rig.ctx, CoreEvents.Mute)

        rig.volume.mute()

        assertEquals(VolumeState.MUTED, rig.ctx.volumeState)
        assertTrue(muted.single().muted)
        assertEquals(1, rig.backend.muteCount)
    }

    @Test
    fun mutingSomethingAlreadyMutedIsNotAnEvent() = runTest {
        val rig = Rig()
        rig.volume.mute()
        val muted = EventLog().capture(rig.ctx, CoreEvents.Mute)

        rig.volume.mute()

        // A repeating keyboard shortcut must not make a chrome flicker.
        assertEquals(0, muted.size)
        assertEquals(1, rig.backend.muteCount)
    }

    @Test
    fun unmutingPutsBackTheLevelFromBeforeTheMute() = runTest {
        val rig = Rig()
        rig.volume.volume(40)
        rig.volume.mute()

        rig.volume.unmute()

        assertEquals(VolumeState.UNMUTED, rig.ctx.volumeState)
        assertEquals(40, rig.ctx.internalVolume)
        assertEquals(1, rig.backend.unmuteCount)
        assertEquals(0.4f, rig.backend.volumesSet.last())
    }

    @Test
    fun aVetoedMuteLeavesTheSoundOn() = runTest {
        val rig = Rig()
        rig.ctx.on(CoreEvents.BeforeMute) { it.preventDefault() }
        val prevented = EventLog().capture(rig.ctx, CoreEvents.MutePrevented)

        rig.volume.mute()

        assertEquals(1, prevented.size)
        assertEquals(VolumeState.UNMUTED, rig.ctx.volumeState)
        assertEquals(0, rig.backend.muteCount)
    }

    @Test
    fun raisingTheVolumeWhileMutedUnmutes() = runTest {
        val rig = Rig()
        rig.volume.mute()
        val muteEvents = EventLog().capture(rig.ctx, CoreEvents.Mute)

        rig.volume.volume(60)

        // Nobody drags the slider up expecting to keep hearing nothing.
        assertEquals(VolumeState.UNMUTED, rig.ctx.volumeState)
        assertEquals(60, rig.ctx.internalVolume)
        assertTrue(muteEvents.any { !it.muted })
    }

    @Test
    fun toggleFlipsBothWays() = runTest {
        val rig = Rig()

        rig.volume.toggleMute()
        assertEquals(VolumeState.MUTED, rig.ctx.volumeState)

        rig.volume.toggleMute()
        assertEquals(VolumeState.UNMUTED, rig.ctx.volumeState)
    }

    @Test
    fun stepUpAndDownMoveByFiveAndStopAtTheEnds() = runTest {
        val rig = Rig()
        rig.volume.volume(50)

        rig.volume.volumeUp()
        assertEquals(55, rig.ctx.internalVolume)

        rig.volume.volumeDown(10)
        assertEquals(45, rig.ctx.internalVolume)

        rig.volume.volume(2)
        rig.volume.volumeDown()
        assertEquals(0, rig.ctx.internalVolume)
    }
}
