// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// Kotlin mirror of the web EventEmitter
// (packages/nomercy-player-core/src/adapters/event-bus/default.ts). Listeners
// are stored insertion-ordered per event name; emit() snapshots that list
// before iterating so an off() called from inside a handler takes effect on
// the next emit, not the one already in progress. E is a phantom marker
// echoing the web EventEmitter<E> brand — real payload typing comes from
// EventKey, never from E.
public class EventEmitter<E> {

    private class Listener(val userFn: Any, val invoke: (Any?) -> Unit, val once: Boolean)

    private val listeners: MutableMap<String, MutableList<Listener>> = mutableMapOf()
    private val firehose: MutableList<(String, Any?) -> Unit> = mutableListOf()

    // Called with (eventName, error) when a listener throws. Default is
    // swallow-and-continue, mirroring the web path's console.error; a host
    // wires this to its own logger. Never rethrown into the dispatch loop.
    public var onListenerError: ((eventName: String, error: Throwable) -> Unit)? = null

    public fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription =
        register(key.name, userFn = fn, invoke = wrap(fn), once = false)

    // Raw hatch for dynamic names such as plugin:<id>:<event> — the sanctioned
    // Any? escape, mirroring the web bare-string overload.
    public fun on(name: String, fn: (Any?) -> Unit): Subscription =
        register(name, userFn = fn, invoke = fn, once = false)

    public fun <T> once(key: EventKey<T>, fn: (T) -> Unit): Subscription =
        register(key.name, userFn = fn, invoke = wrap(fn), once = true)

    // Firehose — called with (name, data) on every emit.
    public fun onAll(fn: (name: String, data: Any?) -> Unit): Subscription {
        firehose.add(fn)
        return idempotent { firehose.remove(fn) }
    }

    public fun <T> off(key: EventKey<T>, fn: (T) -> Unit) {
        removeByReference(key.name, fn)
    }

    public fun <T> emit(key: EventKey<T>, data: T) {
        dispatch(key.name, data)
    }

    public fun emit(name: String, data: Any?) {
        dispatch(name, data)
    }

    public fun hasListeners(name: String): Boolean = listeners[name]?.isNotEmpty() == true

    public fun listenerCount(): Int = firehose.size + listeners.values.sumOf { it.size }

    // Ordered live invokers for name. Used by dispatchBefore (Task 5). Plugin
    // authors: do not call this — use on(event, fn) to listen.
    internal fun listenersOf(name: String): List<(Any?) -> Unit> =
        listeners[name]?.map { it.invoke } ?: emptyList()

    // The single place every Subscription is minted. Same userFn reference
    // registered twice is deduplicated by identity, matching the web Set. A
    // repeat registration still gets back a valid handle so dispose() always
    // works, even though no second Listener was created.
    private fun register(name: String, userFn: Any, invoke: (Any?) -> Unit, once: Boolean): Subscription {
        val list = listeners.getOrPut(name) { mutableListOf() }
        if (list.none { it.userFn === userFn }) {
            list.add(Listener(userFn, invoke, once))
        }
        return idempotent { removeByReference(name, userFn) }
    }

    private fun removeByReference(name: String, userFn: Any) {
        val list = listeners[name] ?: return
        list.removeAll { it.userFn === userFn }
        if (list.isEmpty()) listeners.remove(name)
    }

    // Listener throws are isolated here by design — every registered handler,
    // however it fails, must not stop the remaining ones. Broad Throwable is
    // intentional, not an oversight.
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(name: String, data: Any?) {
        val snapshot = listeners[name]?.toList() ?: emptyList()
        for (listener in snapshot) {
            // once() self-removes before invoking, matching the web wrapper,
            // so a listener that re-emits the same event from inside itself
            // cannot re-trigger it.
            if (listener.once) removeByReference(name, listener.userFn)
            try {
                listener.invoke(data)
            } catch (err: Throwable) {
                onListenerError?.invoke(name, err)
            }
        }
        for (fn in firehose.toList()) {
            try {
                fn(name, data)
            } catch (err: Throwable) {
                onListenerError?.invoke(name, err)
            }
        }
    }
}

// Neither helper below touches EventEmitter state, so both live at file scope
// rather than as class members — keeping EventEmitter's own member count under
// the class function-count gate without duplicating either one per call site.

@Suppress("UNCHECKED_CAST")
private fun <T> wrap(fn: (T) -> Unit): (Any?) -> Unit = { data -> fn(data as T) }

// Wraps a removal so a second Subscription.dispose() is a no-op. Every handle
// EventEmitter hands out goes through here: a plugin's auto-dispose calls
// dispose() blindly, and running remove() twice could otherwise drop a
// listener registered in between the two calls.
private fun idempotent(remove: () -> Unit): Subscription {
    var live = true
    return Subscription {
        if (live) {
            live = false
            remove()
        }
    }
}
