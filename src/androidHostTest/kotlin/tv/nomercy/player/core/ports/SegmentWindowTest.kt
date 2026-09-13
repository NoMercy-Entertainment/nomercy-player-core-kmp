// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentWindowTest {

    private val tenSecondSegments: List<SegmentSpan> = (0 until 6).map { SegmentSpan(it * TEN_S, TEN_S) }

    @Test
    fun aSeekReadsItsSegmentFromTheStartAndTheOneTheWindowRunsInto() {
        assertEquals(listOf(1, 2), segmentsCovering(tenSecondSegments, fromUs = 14_200_000L, windowUs = TEN_S))
    }

    @Test
    fun aSeekOnASegmentBoundaryDoesNotFetchTheSegmentBeforeIt() {
        assertEquals(listOf(2), segmentsCovering(tenSecondSegments, fromUs = 2 * TEN_S, windowUs = TEN_S))
    }

    @Test
    fun aWindowPastTheLastSegmentStopsAtTheEnd() {
        assertEquals(listOf(5), segmentsCovering(tenSecondSegments, fromUs = 55 * 1_000_000L, windowUs = TEN_S))
    }

    @Test
    fun aSeekPastTheEndFetchesNothing() {
        assertEquals(emptyList(), segmentsCovering(tenSecondSegments, fromUs = 60 * 1_000_000L, windowUs = TEN_S))
    }

    private companion object {
        const val TEN_S: Long = 10_000_000L
    }
}
