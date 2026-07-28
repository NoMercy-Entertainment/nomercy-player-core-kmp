// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

// What a leak check measured, whether or not it passed.
//
// The three counts travel with the verdict because "it leaked one listener" and
// "it registered nothing at all and leaked one listener" are different bugs, and
// only the numbers tell them apart.
public data class LeakAssertionResult(
    val subjectId: String,
    val listenersBefore: Int,
    val listenersAfterSetup: Int,
    val listenersAfterTeardown: Int,
    val leaked: Int,
)
