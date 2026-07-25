// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

/**
 * Handle returned by every `on` / `once` / `onAll` registration. Call
 * [unsubscribe] to remove the listener; it is idempotent (a second call is a
 * no-op) so a plugin's auto-dispose can call it blindly.
 */
public class Subscription internal constructor(private val remove: () -> Unit) {
    private var active: Boolean = true
    public val isActive: Boolean get() = active

    public fun unsubscribe() {
        if (!active) return
        active = false
        remove()
    }
}
