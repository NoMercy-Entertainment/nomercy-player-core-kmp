// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

// Everything a plugin starts, in one place that can be switched off.
//
// The web version tracks listeners, timers, animation frames and abort
// controllers separately because the platform gives it four unrelated cleanup
// calls. Here all four are one thing: a coroutine rooted in this registry's
// SupervisorJob. dispose() cancels that job and every timer, loop and collector
// stops with it, so a plugin author cannot forget one.
//
// Whatever genuinely is not a coroutine — removing an event subscription,
// releasing a native handle — goes through addCleanup and runs newest-first on
// dispose, unwinding in the order things were set up.
public open class LifecycleRegistry(scope: CoroutineScope) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val registryScope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)
    private val cleanups: MutableList<() -> Unit> = mutableListOf()
    private var disposed: Boolean = false

    // Called when a cleanup throws. Default is to keep unwinding: one plugin's
    // broken teardown must not strand the cleanups registered before it. A host
    // wires this to its logger so the failure is still visible.
    public var onCleanupError: ((error: Throwable) -> Unit)? = null

    // After dispose, [fn] runs at once rather than being dropped or throwing,
    // so a caller never has to ask whether it is too late to register cleanup.
    public fun addCleanup(fn: () -> Unit) {
        if (disposed) {
            runCleanup(fn)
            return
        }
        cleanups.add(fn)
    }

    // A coroutine that cannot outlive the plugin. The returned Job is for
    // cancelling early; letting it run to completion is fine.
    public fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        if (disposed) return Job().apply { cancel() }
        return registryScope.launch(block = block)
    }

    public fun timeout(delayMs: Long, fn: () -> Unit): Job = launch {
        delay(delayMs)
        fn()
    }

    public fun interval(periodMs: Long, fn: () -> Unit): Job = launch {
        while (isActive) {
            delay(periodMs)
            fn()
        }
    }

    // Reports elapsed milliseconds since the previous tick. Not tied to a real
    // display refresh — commonMain has no such thing — so a visualiser that
    // needs true vsync takes it from its own platform surface and uses this
    // only as the fallback.
    public fun frame(fn: (deltaMs: Long) -> Unit): Job = launch {
        var last = TimeSource.Monotonic.markNow()
        while (isActive) {
            delay(FRAME_INTERVAL_MS)
            val now = TimeSource.Monotonic.markNow()
            fn((now - last).inWholeMilliseconds)
            last = now
        }
    }

    // The Kotlin answer to adding a DOM listener: collection stops when the
    // plugin does.
    public fun <T> listen(flow: Flow<T>, fn: (T) -> Unit): Job = launch {
        flow.collect { value -> fn(value) }
    }

    public fun dispose() {
        if (disposed) return
        disposed = true
        job.cancel()
        val snapshot: List<() -> Unit> = cleanups.toList()
        cleanups.clear()
        for (index in snapshot.indices.reversed()) {
            runCleanup(snapshot[index])
        }
    }

    public fun isDisposed(): Boolean = disposed

    // Broad Throwable on purpose: teardown runs while something is already
    // going wrong, and the remaining cleanups are the point.
    @Suppress("TooGenericExceptionCaught")
    private fun runCleanup(fn: () -> Unit) {
        try {
            fn()
        } catch (error: Throwable) {
            onCleanupError?.invoke(error)
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS: Long = 16L
    }
}
