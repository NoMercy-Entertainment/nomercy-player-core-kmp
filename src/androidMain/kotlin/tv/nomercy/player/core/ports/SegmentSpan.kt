// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

internal data class SegmentSpan(val startUs: Long, val durationUs: Long)

// The segments a seek to [fromUs] reads before it can play [windowUs] of media:
// the one holding the target, from its keyframe, and every one after it the
// window reaches into.
internal fun segmentsCovering(spans: List<SegmentSpan>, fromUs: Long, windowUs: Long): List<Int> =
    spans.indices.filter { index ->
        val span: SegmentSpan = spans[index]
        span.startUs < fromUs + windowUs && span.startUs + span.durationUs > fromUs
    }
