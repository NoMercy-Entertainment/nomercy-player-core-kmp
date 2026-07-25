// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// How to start a piece of media. Everything defaults, so loading something and
// leaving it paused at the beginning is the zero-argument case.
public data class LoadOptions(
    val startPositionMs: Long = 0L,
    val autoplay: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)
