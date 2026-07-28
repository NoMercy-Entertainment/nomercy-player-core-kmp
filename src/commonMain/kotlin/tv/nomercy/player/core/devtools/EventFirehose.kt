// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.devtools

import tv.nomercy.player.core.events.Subscription

// Everything the player announces, by name, without knowing the names first.
//
// The web player inherits this from its emitter, so `player.on('all', ...)` has
// always been there for anyone building a debug view. On this side the emitter
// is a field rather than a base class, and the only code that wanted the
// firehose reached through `player.context.emitter` to get it — which works,
// couples a debug tool to the player's internals, and is the shape of thing
// that breaks the first time the context is refactored.
//
// This is that reach, named. A player offers it, a tool takes it, and neither
// has to know anything else about the other. It is also what makes the
// inspector testable against a stand-in: the fake player offers the same
// firehose, so what a test drives is the same subscription a real player gives.
//
// It does NOT see the before* dispatch. Those listeners are invoked directly so
// a listener can cut the chain, and they never pass through emit(). Anything
// watching the cancellable seam has to subscribe by name.
public fun interface EventFirehose {
    public fun onAll(fn: (name: String, data: Any?) -> Unit): Subscription
}
