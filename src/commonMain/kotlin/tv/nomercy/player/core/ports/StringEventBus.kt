// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// The small bus a backend composes to satisfy MediaBackend.on/off.
//
// Locked because engine callbacks arrive on whatever thread the engine feels
// like: VLCJ's native callback thread, AVPlayer's main queue, ExoPlayer's
// application thread. Registration from a controller and emission from a native
// thread genuinely race, and a plain MutableMap loses listeners when they do.
//
// Listeners are snapshotted before iterating, so a handler that unsubscribes
// itself does not disturb the emit already running.
public class StringEventBus {
    private val lock = SynchronizedObject()
    private val listeners: MutableMap<String, MutableList<(Any?) -> Unit>> = mutableMapOf()

    public fun on(event: String, fn: (Any?) -> Unit): Unit = synchronized(lock) {
        listeners.getOrPut(event) { mutableListOf() }.add(fn)
    }

    public fun off(event: String, fn: (Any?) -> Unit): Unit = synchronized(lock) {
        listeners[event]?.remove(fn)
        Unit
    }

    // The lock covers taking the snapshot, not running the listeners. Holding it
    // through a callback would let a listener that emits deadlock the engine
    // thread it was called from.
    public fun emit(event: String, data: Any? = null) {
        val snapshot: List<(Any?) -> Unit> = synchronized(lock) {
            listeners[event]?.toList() ?: emptyList()
        }
        for (fn in snapshot) fn(data)
    }

    public fun clear(): Unit = synchronized(lock) {
        listeners.clear()
    }
}
