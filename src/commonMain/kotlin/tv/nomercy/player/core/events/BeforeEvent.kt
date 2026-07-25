// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// The cancellable, mutable, async-aware payload every before* event carries.
// Kotlin mirror of the web BeforeEvent<TData> (types/events.ts).
//
// This is the seam plugins intercept a transport action through: reshape the
// payload by assigning data, refuse the action with preventDefault(), take the
// event away from later listeners with stopImmediatePropagation(), or hold the
// action open with delay { } until an async decision lands.
public class BeforeEvent<T>(public var data: T) {
    private var prevented: Boolean = false
    private var propagationStopped: Boolean = false
    private val delays: MutableList<suspend () -> Unit> = mutableListOf()

    // Skips the default action and the post-event that would have followed it.
    // Latches: a later listener cannot un-prevent what an earlier one refused.
    public fun preventDefault() {
        prevented = true
    }

    public fun isDefaultPrevented(): Boolean = prevented

    // Skips every remaining listener for this dispatch. Does not by itself
    // prevent the default action — the two decisions are independent, matching
    // the DOM and the web core.
    public fun stopImmediatePropagation() {
        propagationStopped = true
    }

    public fun isPropagationStopped(): Boolean = propagationStopped

    // Holds the action open until [block] completes. Multiple gates from
    // multiple listeners all run concurrently and all must succeed; see
    // awaitDelayGates for the composition and the timeout.
    public fun delay(block: suspend () -> Unit) {
        delays.add(block)
    }

    public fun isDelayed(): Boolean = delays.isNotEmpty()

    // Snapshot of the registered gates. Copied rather than exposed, so a
    // listener that registers a gate from inside another gate cannot mutate
    // the list being iterated.
    internal fun consumeDelays(): List<suspend () -> Unit> = delays.toList()
}
