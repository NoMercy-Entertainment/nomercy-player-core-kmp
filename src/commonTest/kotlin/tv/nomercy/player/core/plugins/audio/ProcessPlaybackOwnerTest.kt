// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessPlaybackOwnerTest {

    @AfterTest
    fun reset() {
        ProcessPlaybackOwner.resetForTest()
    }

    @Test
    fun aSecondClaimPausesTheFirstHolder() {
        var firstPaused = false
        val second: () -> Unit = {}

        ProcessPlaybackOwner.claim { firstPaused = true }
        ProcessPlaybackOwner.claim(second)

        assertTrue(firstPaused, "video and music playing at once is exactly what this exists to stop")
    }

    @Test
    fun theFirstClaimHasNothingToPauseAndDoesNotCrashLookingForIt() {
        ProcessPlaybackOwner.claim {}
    }

    @Test
    fun claimingAgainWithTheSameCallbackDoesNotPauseItself() {
        var calls = 0
        val holder: () -> Unit = { calls++ }

        ProcessPlaybackOwner.claim(holder)
        ProcessPlaybackOwner.claim(holder)

        assertEquals(0, calls, "a player resuming itself paused itself")
    }

    @Test
    fun releasingLeavesTheSlotOpenForTheNextClaim() {
        var secondPaused = false
        val first: () -> Unit = {}

        ProcessPlaybackOwner.claim(first)
        ProcessPlaybackOwner.release(first)
        ProcessPlaybackOwner.claim { secondPaused = true }
        ProcessPlaybackOwner.claim {}

        assertTrue(secondPaused, "the slot released by the first holder was never handed to the second")
    }

    @Test
    fun releasingSomeoneElsesClaimDoesNothing() {
        var currentPaused = false

        ProcessPlaybackOwner.claim { currentPaused = true }
        // A stale release from a player that already lost the slot to a
        // newer claim — must not clear the real current holder.
        ProcessPlaybackOwner.release {}
        ProcessPlaybackOwner.claim {}

        assertTrue(currentPaused, "a stale release cleared a slot it did not own")
    }
}
