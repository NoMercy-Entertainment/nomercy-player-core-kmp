// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.dsp.perceptualGain
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.VolumeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HALFWAY = 50
private const val FULL = 100
private const val PRE_MUTE = 70
private const val OUT_OF_RANGE = 500

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
        // Public scale is 0..100, the engine's is 0..1, and the conversion
        // happens once here — through the taper, so full scale is unity.
        assertEquals(1.0f, rig.backend.volumesSet.last())

        rig.volume.volume(50)
        assertEquals(0.25f, rig.backend.volumesSet.last())
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
    fun aRedirectedLevelIsStillClamped() = runTest {
        val rig = Rig()
        rig.ctx.on(CoreEvents.BeforeVolume) { it.data = it.data.copy(level = OUT_OF_RANGE) }

        rig.volume.volume(HALFWAY)

        // Only the caller's number was clamped, so a listener answering with one
        // of its own reached the engine unchecked — five times full scale, which
        // an engine either refuses or distorts.
        assertEquals(FULL, rig.ctx.internalVolume)
        assertEquals(1.0f, rig.backend.volumesSet.last())
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
        assertEquals(perceptualGain(0.4f), rig.backend.volumesSet.last())
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
    fun theEngineIsHandedAPerceptualGainRatherThanTheSliderPosition() = runTest {
        val rig = Rig()

        rig.volume.volume(HALFWAY)

        // The ear is logarithmic. Handing the engine the slider's own position
        // puts the whole audible range in the bottom third of the strip, so the
        // web taper is applied before the value reaches an engine: position 0.5
        // is a quarter of full amplitude. This was absent here, and no engine
        // applied it either, so the same slider position was audibly louder on
        // every native platform than on the web — including mid-session, when a
        // Connect handover moves playback between the two.
        assertEquals(0.25f, rig.backend.volumesSet.last())

        // The ends are exactly the ends: silence is silence and full is unity,
        // with no floor to work around.
        rig.volume.volume(0)
        assertEquals(0.0f, rig.backend.volumesSet.last())

        rig.volume.volume(FULL)
        assertEquals(1.0f, rig.backend.volumesSet.last())
    }

    @Test
    fun droppingToSilenceWhileMutedDoesNotForgetTheLevelToComeBackTo() = runTest {
        val rig = Rig()
        rig.volume.volume(PRE_MUTE)
        rig.volume.mute()

        // Ordinary: a hardware key or a chrome writing 0 while already muted.
        rig.volume.volume(0)
        rig.volume.unmute()

        // The stored level was being overwritten with the 0 that arrived during
        // the mute, so unmuting restored silence and the viewer's only way back
        // was to find the slider — with nothing coming out to aim by.
        assertEquals(PRE_MUTE, rig.ctx.internalVolume)
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
