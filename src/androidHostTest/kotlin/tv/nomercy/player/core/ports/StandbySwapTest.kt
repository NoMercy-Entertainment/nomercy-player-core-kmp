// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandbySwapTest {

    @Test
    fun theSeekThatWasPrerolledSwaps() {
        assertTrue(StandbySwap.matches(prerolledMs = 142_058L, requestedMs = 142_058L))
    }

    @Test
    fun aSeekRoundedDifferentlyStillSwaps() {
        assertTrue(StandbySwap.matches(prerolledMs = 142_058L, requestedMs = 142_057L))
    }

    @Test
    fun aSeekSomewhereElseDecodesInstead() {
        assertFalse(StandbySwap.matches(prerolledMs = 142_058L, requestedMs = 150_000L))
    }

    @Test
    fun nothingPrerolledNeverSwaps() {
        assertFalse(StandbySwap.matches(prerolledMs = null, requestedMs = 142_058L))
    }
}
