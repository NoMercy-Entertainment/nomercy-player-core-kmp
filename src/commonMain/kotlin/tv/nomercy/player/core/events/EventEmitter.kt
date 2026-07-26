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
@Suppress("TooManyFunctions")
public class EventEmitter<E> {

    private class Listener(val userFn: Any, val invoke: (Any?) -> Unit)

    private val listeners: MutableMap<String, MutableList<Listener>> = mutableMapOf()
    private val firehose: MutableList<(String, Any?) -> Unit> = mutableListOf()

    // The events being dispatched right now, outermost first, because a
    // listener that emits is inside two of them.
    //
    // What it is for: a before-listener deciding whether to allow an action
    // needs to know which chain it is inside. "Refuse a seek" and "refuse a
    // seek that came from the queue advancing to the next item" are different
    // rules, and without this the listener cannot tell them apart.
    //
    // A plain list, not a synchronised one. The player dispatches on one thread
    // by contract; a lock here would suggest it does not.
    private val dispatchStack: MutableList<String> = mutableListOf()

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

    // Firehose — called with (name, data) on every emit. It does NOT see the
    // before* dispatch: those listeners are invoked directly so that
    // stopImmediatePropagation can cut the loop, and they never pass through
    // emit(). Anything observing the cancellable seam must subscribe by name.
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

    // Runs the cancellable before* dispatch for [key] and reports what the
    // caller should do. Kotlin mirror of the web runDispatchBefore.
    //
    // Listeners run in registration order against one shared, mutable
    // BeforeEvent; the first stopImmediatePropagation ends the loop. Any delay
    // gates they registered are then awaited together under [timeoutMs].
    //
    // Order matters and is not arbitrary: the gates are resolved before
    // preventDefault is read, so a listener that both refuses the action and
    // registers a gate still reports the gate's failure. The gate is the more
    // specific answer to why the action did not happen.
    @Suppress("TooGenericExceptionCaught")
    public suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
        timeoutMs: Long = DEFAULT_BEFORE_TIMEOUT_MS,
    ): BeforeDispatchResult<T> {
        val event: BeforeEvent<T> = BeforeEvent(data)
        dispatchStack += key.name
        try {
            return runBefore(key.name, event, timeoutMs)
        } finally {
            dispatchStack.removeAt(dispatchStack.lastIndex)
        }
    }

    // The body of dispatchBefore, so the stack push above wraps every exit —
    // including the two early returns, which is where a hand-placed pop gets
    // forgotten and leaves the stack claiming a dispatch that finished.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runBefore(
        name: String,
        event: BeforeEvent<T>,
        timeoutMs: Long,
    ): BeforeDispatchResult<T> {
        for (listener in listenersOf(name)) {
            if (event.isPropagationStopped()) break
            try {
                listener(event)
            } catch (err: Throwable) {
                onListenerError?.invoke(name, err)
            }
        }

        val gateFailure: String? = awaitDelayGates(event.consumeDelays(), timeoutMs)
        if (gateFailure != null) {
            return BeforeDispatchResult(prevented = true, data = event.data, reason = gateFailure)
        }
        if (event.isDefaultPrevented()) {
            return BeforeDispatchResult(prevented = true, data = event.data, reason = PreventReason.ListenerPrevented)
        }
        return BeforeDispatchResult(prevented = false, data = event.data, reason = null)
    }

    public fun hasListeners(name: String): Boolean = listeners[name]?.isNotEmpty() == true

    public fun listenerCount(): Int = firehose.size + listeners.values.sumOf { it.size }

    // Ordered live invokers for name, already snapshotted. Used by
    // dispatchBefore, which needs to call listeners directly rather than
    // through emit() so stopImmediatePropagation can end the loop. Plugin
    // authors: do not call this — use on(event, fn) to listen.
    internal fun listenersOf(name: String): List<(Any?) -> Unit> =
        listeners[name]?.map { it.invoke } ?: emptyList()

    // The single place every Subscription is minted. Same userFn reference
    // registered twice is deduplicated by identity, matching the web Set. A
    // repeat registration still gets back a valid handle so dispose() always
    // works, even though no second Listener was created.
    //
    // once removes itself inside the wrapper rather than in the dispatch loop,
    // so both dispatch paths get the behaviour: a before-listener registered
    // with once() must fire once too, and dispatchBefore never sees this flag.
    private fun register(name: String, userFn: Any, invoke: (Any?) -> Unit, once: Boolean): Subscription {
        val list = listeners.getOrPut(name) { mutableListOf() }
        if (list.none { it.userFn === userFn }) {
            val call: (Any?) -> Unit = if (once) {
                { data ->
                    removeByReference(name, userFn)
                    invoke(data)
                }
            } else {
                invoke
            }
            list.add(Listener(userFn, call))
        }
        return idempotent { removeByReference(name, userFn) }
    }

    private fun removeByReference(name: String, userFn: Any) {
        val list = listeners[name] ?: return
        list.removeAll { it.userFn === userFn }
        if (list.isEmpty()) listeners.remove(name)
    }

    // Listener throws are isolated here and in dispatchBefore by design — every
    // registered handler, however it fails, must not stop the remaining ones.
    // Broad Throwable is intentional, not an oversight.
    @Suppress("TooGenericExceptionCaught")
    public fun dispatching(): List<String> = dispatchStack.toList()

    // removeAt(lastIndex) rather than removeLast(), and this is not a style
    // preference. On JVM target 21 Kotlin resolves MutableList.removeLast() to
    // java.util.List.removeLast() from SequencedCollection rather than to its
    // own extension, and that interface method does not exist on every Android
    // device the library supports.
    //
    // It cannot be gated on an API level either. Two phones both reporting API
    // 34 disagreed: the one with an updated ART mainline module had the method
    // and the Android TV box that has never taken a mainline update did not, so
    // every dispatch on it died with NoSuchMethodError. That is 271 of 697
    // tests, and in a shipped build it would be the whole event system.
    private fun dispatch(name: String, data: Any?) {
        dispatchStack += name
        try {
            deliver(name, data)
        } finally {
            dispatchStack.removeAt(dispatchStack.lastIndex)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deliver(name: String, data: Any?) {
        for (listener in listenersOf(name)) {
            try {
                listener(data)
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

    public companion object {
        // The web core's cap on how long every delay gate together may hold an
        // action open before it is refused.
        public const val DEFAULT_BEFORE_TIMEOUT_MS: Long = 10_000
    }
}
