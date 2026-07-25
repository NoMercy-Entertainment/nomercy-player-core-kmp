// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Wall-clock milliseconds since the epoch.
//
// A port rather than a direct call so a test can decide what time it is.
// Anything that expires, retries with backoff, or stamps a metric reads this.
public interface Clock {
    public fun now(): Long
}

public expect fun defaultClock(): Clock
