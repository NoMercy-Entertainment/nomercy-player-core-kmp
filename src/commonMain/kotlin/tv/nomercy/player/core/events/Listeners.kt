// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// Registration plumbing shared by every path that hands out a Subscription.
// Neither helper touches EventEmitter state, so both live here rather than as
// members: the emitter's own function count stays readable, and the erasure
// boundary is one grep away instead of buried mid-class.

// The single type-erasure boundary in the library. A typed listener is stored
// as (Any?) -> Unit so one listener list can serve every payload type; the cast
// is safe because the only way to reach a listener is through the EventKey it
// was registered with, and that key carries T.
@Suppress("UNCHECKED_CAST")
internal fun <T> wrap(fn: (T) -> Unit): (Any?) -> Unit = { data -> fn(data as T) }

// Wraps a removal so a second Subscription.dispose() is a no-op. Every handle
// EventEmitter hands out goes through here: a plugin's auto-dispose calls
// dispose() blindly, and running remove() twice could otherwise drop a
// listener registered in between the two calls.
internal fun idempotent(remove: () -> Unit): Subscription {
    var live = true
    return Subscription {
        if (live) {
            live = false
            remove()
        }
    }
}
