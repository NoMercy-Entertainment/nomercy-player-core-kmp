// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

// What the connection is worth, in bits per second.
//
// The library cannot measure this itself and should not pretend to. An engine
// knows what its own segments cost; a host may know more — a system bandwidth
// API, a CDN hint, a measurement it already takes for something else — and the
// estimator is how it says so.
//
// Zero means nobody has answered yet, and it is the honest default. Guessing a
// number would make adaptation decisions on a measurement that never happened.
public class BandwidthController {

    private var estimator: (() -> Int)? = null

    // The last answer, or zero.
    //
    // Read through the estimator every time rather than cached, because the
    // whole point of an injectable one is that it reflects a connection that
    // moves. A cached estimate is a measurement from whenever the caller last
    // thought to ask.
    public fun bandwidth(): Int = estimator?.invoke()?.coerceAtLeast(0) ?: 0

    public fun bandwidthEstimator(): (() -> Int)? = estimator

    public fun bandwidthEstimator(fn: (() -> Int)?) {
        estimator = fn
    }
}
