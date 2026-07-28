// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.TimeRange
import tv.nomercy.player.core.ports.covers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

// An engine that reports real ranges, which is what AVFoundation does.
private class RangeReportingBackend : FakeMediaBackend() {
    var loaded: List<TimeRange> = emptyList()
    var reachable: List<TimeRange> = emptyList()

    override fun bufferedRanges(): List<TimeRange> = loaded
    override fun seekableRanges(): List<TimeRange> = reachable
}

// Where data is and where the playhead may go.
//
// Only some engines can answer these: AVFoundation reports both directly,
// Media3 and libVLC report a single frontier and no holes. The tests here are
// about the player filling in what it can from what it does know, rather than
// making every engine implement a shape it has no data for.
class TimeRangeTest {

    @Test
    fun anEnginesOwnRangesArePassedThroughUntouched() {
        val backend = RangeReportingBackend()
        val player = ComposedPlayer(backend = backend)
        backend.loaded = listOf(TimeRange(0.0, 120.0), TimeRange(300.0, 420.0))

        assertEquals(listOf(TimeRange(0.0, 120.0), TimeRange(300.0, 420.0)), player.bufferedRanges())
    }

    @Test
    fun anEngineWithOnlyAFrontierGetsItRestatedAsARange() {
        // One shape for a caller to read, rather than two depending on which
        // engine it happened to get.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        backend.bufferedValue = 90.0

        assertEquals(listOf(TimeRange(0.0, 90.0)), player.bufferedRanges())
    }

    @Test
    fun nothingBufferedIsAnEmptyListNotAZeroLengthRange() {
        // A zero-length range at the origin would draw as a sliver of buffer
        // that is not there.
        assertEquals(emptyList(), ComposedPlayer(backend = FakeMediaBackend()).bufferedRanges())
    }

    @Test
    fun aCompleteFileIsSeekableEndToEndEvenWhenTheEngineSaysNothing() {
        // An empty list would read as "seeking is impossible", and a chrome
        // would disable its scrubber over a file that seeks perfectly well.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        backend.durationValue = 1_800.0
        backend.fire(CanonicalBackendEvent.TIME_UPDATE)

        assertEquals(listOf(TimeRange(0.0, 1_800.0)), player.seekable())
    }

    @Test
    fun aLiveStreamReportsNothingSeekableRatherThanGuessing() {
        // The duration is unknown and nobody knows where the window starts.
        // Empty is the truth here, not a fallback.
        assertEquals(emptyList(), ComposedPlayer(backend = FakeMediaBackend()).seekable())
    }

    @Test
    fun anEnginesSeekableWindowWinsOverTheDerivedOne() {
        // A live stream with a DVR window is exactly the case the derived answer
        // gets wrong: it starts at zero and the real window does not.
        val backend = RangeReportingBackend()
        val player = ComposedPlayer(backend = backend)
        backend.durationValue = 3_600.0
        backend.fire(CanonicalBackendEvent.TIME_UPDATE)
        backend.reachable = listOf(TimeRange(3_000.0, 3_600.0))

        assertEquals(listOf(TimeRange(3_000.0, 3_600.0)), player.seekable())
    }

    @Test
    fun aGapBetweenBufferedRangesIsVisibleToACallerThatAsks() {
        // The whole reason for ranges over one number: seeking into a gap is
        // legal and stalls, and a chrome that knows can show the difference
        // instead of promising a jump it cannot deliver.
        val ranges: List<TimeRange> = listOf(TimeRange(0.0, 120.0), TimeRange(300.0, 420.0))

        assertTrue(ranges.covers(60.0))
        assertTrue(ranges.covers(360.0))
        assertFalse(ranges.covers(200.0), "a gap between ranges was reported as buffered")
    }

    @Test
    fun aRangeKnowsHowLongItIs() {
        assertEquals(120.0, TimeRange(300.0, 420.0).duration)
    }

    @Test
    fun anInvertedRangeHasNoNegativeLength() {
        // An engine reporting end before start is malformed, and a negative
        // width drawn on a scrubber is a bar extending backwards off the track.
        assertEquals(0.0, TimeRange(420.0, 300.0).duration)
    }
}
