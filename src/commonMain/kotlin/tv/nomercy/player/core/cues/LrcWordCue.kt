// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

/**
 * One word of a lyric line, with when it lands.
 *
 * Word timings are what make a highlight follow the singer rather than jump a
 * line at a time, and they are per word because that is the resolution the
 * enhanced LRC format carries. A parser that kept only line timings would throw
 * away the whole reason the file has them.
 */
public data class LrcWordCue(
    val start: Double,
    val text: String,
)
