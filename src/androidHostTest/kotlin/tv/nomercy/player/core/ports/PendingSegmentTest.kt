// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A segment 404 and a playlist 404 are different answers.
 *
 * Both arrive as the same status from the same server, and the retry schedule
 * applied to both: twenty tries over roughly a minute, which is right for an
 * encoder still working through a timeline and wrong for an item that has no
 * media at all. The viewer of an unencoded episode watched a spinner for the
 * whole minute before being told anything.
 */
class PendingSegmentTest {

    @Test
    fun `a media segment 404 is a promise the encoder has not reached`() {
        assertTrue(PendingSegment.isPending(C.DATA_TYPE_MEDIA, 404))
    }

    @Test
    fun `a manifest 404 is the server having no media for this item`() {
        // The whole point of the split: this used to be indistinguishable from
        // a pending segment and inherited its minute of patience.
        assertFalse(PendingSegment.isPending(C.DATA_TYPE_MANIFEST, 404))
    }

    @Test
    fun `only media loads get the raised retry count`() {
        assertTrue(PendingSegment.appliesTo(C.DATA_TYPE_MEDIA))

        listOf(
            C.DATA_TYPE_MANIFEST,
            C.DATA_TYPE_DRM,
            C.DATA_TYPE_MEDIA_INITIALIZATION,
            C.DATA_TYPE_TIME_SYNCHRONIZATION,
            C.DATA_TYPE_UNKNOWN,
        ).forEach { type ->
            assertFalse(PendingSegment.appliesTo(type), "data type $type must not wait like a segment")
        }
    }

    @Test
    fun `a status that is not 404 never counts as pending, whatever it loaded`() {
        // 403 and 500 are not "not yet". Treating them as pending would spend a
        // minute on a failure that the default schedule surfaces in seconds.
        listOf(200, 403, 410, 500, 503).forEach { status ->
            assertFalse(
                PendingSegment.isPending(C.DATA_TYPE_MEDIA, status),
                "status $status must not be read as a pending segment",
            )
        }
    }

    // "Not yet" is a video idea — music never live-transcodes, so a 404 on a
    // music file always means "there is no such file". Measured live, real
    // phone, 2026-09-08: a genuinely missing music file was held on the video
    // schedule (twenty tries, ~57 seconds) before the app heard about it.
    @Test
    fun `a media 404 is never pending for the engine that never live-transcodes`() {
        assertFalse(PendingSegment.appliesTo(C.DATA_TYPE_MEDIA, allowPendingSegments = false))
        assertFalse(
            PendingSegment.isPending(C.DATA_TYPE_MEDIA, 404, allowPendingSegments = false),
            "music's engine must fail a media 404 on the default schedule, not wait a minute for it",
        )
    }

    @Test
    fun `video keeps its live-transcode leniency untouched`() {
        assertTrue(PendingSegment.appliesTo(C.DATA_TYPE_MEDIA, allowPendingSegments = true))
        assertTrue(PendingSegment.isPending(C.DATA_TYPE_MEDIA, 404, allowPendingSegments = true))
    }
}
