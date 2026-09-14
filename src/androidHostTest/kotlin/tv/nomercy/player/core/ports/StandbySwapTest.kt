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

    @Test
    fun theNextEpisodeLoadedWhereItWasPrerolledSwaps() {
        assertTrue(StandbySwap.matchesSource(EPISODE_TWO, 90_120L, EPISODE_TWO, 90_100L))
    }

    @Test
    fun anotherEpisodeAtTheSameSecondLoadsFresh() {
        assertFalse(StandbySwap.matchesSource(EPISODE_TWO, 90_120L, EPISODE_THREE, 90_120L))
    }

    @Test
    fun aSeekToTheEndWithTheNextEpisodeReadyEndsAtOnce() {
        assertTrue(StandbySwap.endsIntoNextSource(EPISODE_TWO, EPISODE_ONE, 1_506_000L, 1_506_088L))
    }

    @Test
    fun aSeekShortOfTheEndStillSeeks() {
        assertFalse(StandbySwap.endsIntoNextSource(EPISODE_TWO, EPISODE_ONE, 1_506_000L, 1_404_820L))
    }

    @Test
    fun aSeekToTheEndWithNothingPrerolledStillSeeks() {
        assertFalse(StandbySwap.endsIntoNextSource(null, EPISODE_ONE, 1_506_000L, 1_506_088L))
    }

    @Test
    fun aStandbyForThisEpisodeDoesNotEndIt() {
        assertFalse(StandbySwap.endsIntoNextSource(EPISODE_ONE, EPISODE_ONE, 1_506_000L, 1_506_088L))
    }

    @Test
    fun aStandbyForNoSourceNeverSwaps() {
        assertFalse(StandbySwap.matchesSource(null, 0L, null, 0L))
    }
}

private const val EPISODE_ONE = "https://media.example.test/show/S01E01/S01E01.m3u8"
private const val EPISODE_TWO = "https://media.example.test/show/S01E02/S01E02.m3u8"
private const val EPISODE_THREE = "https://media.example.test/show/S01E03/S01E03.m3u8"
