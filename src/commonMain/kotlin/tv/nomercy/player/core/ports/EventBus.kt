// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * The bus the player publishes on and a host listens to.
 *
 * [off] with no handler drops every listener for that event, and [offAll] drops
 * everything — which is what a teardown needs. A chrome unmounting cannot hold
 * references to the closures it registered, so without a way to unsubscribe in
 * bulk each remount leaves the previous one subscribed and the third one draws
 * three times per frame.
 */
public interface EventBus {

    public fun on(event: String, handler: (Any?) -> Unit)

    /** Fires at most once, then removes itself whether or not anyone unsubscribes. */
    public fun once(event: String, handler: (Any?) -> Unit)

    /** One handler, or every handler for that event when null. */
    public fun off(event: String, handler: ((Any?) -> Unit)? = null)

    /** Everything, for every event. The teardown case. */
    public fun offAll()

    public fun emit(event: String, data: Any? = null)
}
