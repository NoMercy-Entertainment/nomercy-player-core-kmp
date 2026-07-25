// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Collision-resistant ids for things that need to be told apart across a
// session: a request, a transition, a queued item. A port so a test can make
// them sequential and readable instead of random.
public interface IdGenerator {
    public fun next(): String
}

public expect fun defaultIdGenerator(): IdGenerator
