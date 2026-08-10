// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.ports.FocusLossKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFocusArbiterTest {

    @Test
    fun transientLossPausesAndGainResumesIt() {
        val arbiter = AudioFocusArbiter()

        assertEquals(FocusAction.Pause, arbiter.onFocusLost(FocusLossKind.TRANSIENT))
        assertEquals(FocusAction.Resume, arbiter.onFocusGained())
    }

    @Test
    fun duckCapableTransientLossDucksAndNeverPauses() {
        val arbiter = AudioFocusArbiter()

        val action: FocusAction = arbiter.onFocusLost(FocusLossKind.TRANSIENT_CAN_DUCK)

        assertEquals(FocusAction.SetDuckGain(0.2f), action)
        assertEquals(false, arbiter.isPausedByArbiter)
    }

    @Test
    fun gainAfterADuckUnducksRatherThanResuming() {
        val arbiter = AudioFocusArbiter()

        arbiter.onFocusLost(FocusLossKind.TRANSIENT_CAN_DUCK)

        assertEquals(FocusAction.SetDuckGain(1.0f), arbiter.onFocusGained())
    }

    @Test
    fun permanentLossPauses() {
        val arbiter = AudioFocusArbiter()

        assertEquals(FocusAction.Pause, arbiter.onFocusLost(FocusLossKind.PERMANENT))
    }

    @Test
    fun becomingNoisyPauses() {
        val arbiter = AudioFocusArbiter()

        assertEquals(FocusAction.Pause, arbiter.onBecomingNoisy())
        assertEquals(true, arbiter.isPausedByArbiter)
    }

    @Test
    fun becomingNoisyThenGainResumesItLikeATransientLoss() {
        val arbiter = AudioFocusArbiter()

        arbiter.onBecomingNoisy()

        assertEquals(FocusAction.Resume, arbiter.onFocusGained())
    }

    @Test
    fun aUserPauseIsNeverResumedByAGrant() {
        val arbiter = AudioFocusArbiter()

        arbiter.onFocusLost(FocusLossKind.TRANSIENT)
        arbiter.onUserPaused()

        assertEquals(FocusAction.None, arbiter.onFocusGained())
    }

    @Test
    fun aUserPauseIsNeverResumedByBecomingNoisyRecovering() {
        val arbiter = AudioFocusArbiter()

        arbiter.onUserPaused()

        assertEquals(FocusAction.None, arbiter.onBecomingNoisy())
        assertEquals(FocusAction.None, arbiter.onFocusGained())
    }

    @Test
    fun aLossArrivingAfterAUserPauseDoesNothing() {
        val arbiter = AudioFocusArbiter()

        arbiter.onUserPaused()

        assertEquals(FocusAction.None, arbiter.onFocusLost(FocusLossKind.TRANSIENT))
        assertEquals(FocusAction.None, arbiter.onFocusLost(FocusLossKind.TRANSIENT_CAN_DUCK))
        assertEquals(FocusAction.None, arbiter.onFocusLost(FocusLossKind.PERMANENT))
    }

    @Test
    fun resumingPlaybackAfterAUserPauseClearsTheFlagForTheNextRealLoss() {
        val arbiter = AudioFocusArbiter()

        arbiter.onUserPaused()
        arbiter.onUserResumed()

        assertEquals(FocusAction.Pause, arbiter.onFocusLost(FocusLossKind.TRANSIENT))
        assertEquals(FocusAction.Resume, arbiter.onFocusGained())
    }

    @Test
    fun aGrantWithNothingPausedOrDuckedUnducksRatherThanResuming() {
        val arbiter = AudioFocusArbiter()

        assertEquals(FocusAction.SetDuckGain(1.0f), arbiter.onFocusGained())
        assertEquals(false, arbiter.isPausedByArbiter)
    }

    @Test
    fun aCustomDuckGainIsUsedRatherThanTheDefault() {
        val arbiter = AudioFocusArbiter(duckGain = 0.5f)

        assertEquals(FocusAction.SetDuckGain(0.5f), arbiter.onFocusLost(FocusLossKind.TRANSIENT_CAN_DUCK))
    }
}
