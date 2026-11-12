// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Keeps the last item during a transient empty cursor. The scheduler is the
// main looper in production and a manual clock in the host regression test.
internal class NowPlayingBlankDebouncer<T>(
    private val delayMs: Long,
    private val scheduler: UpdateScheduler,
    private val publish: (T) -> Unit,
    private val blank: () -> Unit,
) {
    private var cancelPendingBlank: (() -> Unit)? = null

    fun setNowPlaying(item: T) {
        cancel()
        publish(item)
    }

    fun clearNowPlaying() {
        cancel()
        cancelPendingBlank = scheduler.schedule(delayMs) {
            cancelPendingBlank = null
            blank()
        }
    }

    fun cancel() {
        cancelPendingBlank?.invoke()
        cancelPendingBlank = null
    }
}
