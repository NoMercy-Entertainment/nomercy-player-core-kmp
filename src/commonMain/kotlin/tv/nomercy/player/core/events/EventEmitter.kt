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

    private class Listener(
        val userFn: Any,
        val invoke: (Any?) -> Unit,
        val once: Boolean,
    )

    // Per-name bookkeeping, split out so EventEmitter's own members stay
    // registration/dispatch orchestration rather than list mechanics.
    private class Listeners {
        private val byName: MutableMap<String, MutableList<Listener>> = mutableMapOf()

        fun add(name: String, userFn: Any, invoke: (Any?) -> Unit, once: Boolean) {
            val list = byName.getOrPut(name) { mutableListOf() }
            if (list.none { it.userFn === userFn }) {
                list.add(Listener(userFn, invoke, once))
            }
        }

        fun removeByReference(name: String, userFn: Any) {
            val list = byName[name] ?: return
            list.removeAll { it.userFn === userFn }
            if (list.isEmpty()) byName.remove(name)
        }

        fun snapshotFor(name: String): List<Listener> = byName[name]?.toList() ?: emptyList()

        fun hasAny(name: String): Boolean = byName[name]?.isNotEmpty() == true

        fun totalCount(): Int = byName.values.sumOf { it.size }

        fun invokersFor(name: String): List<(Any?) -> Unit> = byName[name]?.map { it.invoke } ?: emptyList()
    }

    private val listeners = Listeners()
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
        listeners.removeByReference(key.name, fn)
    }

    public fun <T> emit(key: EventKey<T>, data: T) {
        dispatch(key.name, data)
    }

    public fun emit(name: String, data: Any?) {
        dispatch(name, data)
    }

    public fun hasListeners(name: String): Boolean = listeners.hasAny(name)

    public fun listenerCount(): Int = firehose.size + listeners.totalCount()

    // Ordered live invokers for name. Used by dispatchBefore (Task 5). Plugin
    // authors: do not call this — use on(event, fn) to listen.
    internal fun listenersOf(name: String): List<(Any?) -> Unit> = listeners.invokersFor(name)

    @Suppress("UNCHECKED_CAST")
    private fun <T> wrap(fn: (T) -> Unit): (Any?) -> Unit = { data -> fn(data as T) }

    // The single place every Subscription is minted. Same userFn reference
    // registered twice is deduplicated by identity, matching the web Set. A
    // repeat registration still gets back a valid handle so dispose() always
    // works, even though no second Listener was created.
    private fun register(name: String, userFn: Any, invoke: (Any?) -> Unit, once: Boolean): Subscription {
        listeners.add(name, userFn, invoke, once)
        return idempotent { listeners.removeByReference(name, userFn) }
    }

    // Wraps a removal so a second Subscription.dispose() is a no-op. Every
    // handle this class hands out goes through here: a plugin's auto-dispose
    // calls dispose() blindly, and running remove() twice could otherwise drop
    // a listener registered in between the two calls.
    private fun idempotent(remove: () -> Unit): Subscription {
        var live = true
        return Subscription {
            if (live) {
                live = false
                remove()
            }
        }
    }

    // Listener throws are isolated here by design — every registered handler,
    // however it fails, must not stop the remaining ones. Broad Throwable is
    // intentional, not an oversight.
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(name: String, data: Any?) {
        for (listener in listeners.snapshotFor(name)) {
            // once() self-removes before invoking, matching the web wrapper,
            // so a listener that re-emits the same event from inside itself
            // cannot re-trigger it.
            if (listener.once) listeners.removeByReference(name, listener.userFn)
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
