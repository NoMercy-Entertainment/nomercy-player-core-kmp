// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

/**
 * The one thread allowed to read mpv's event queue, and what it found.
 *
 * mpv's queue is single-consumer, so this owns it rather than exposing it: a
 * second reader would take events the first needed and the loss would show up
 * as an error that sometimes reports itself.
 *
 * The timeout is what makes parking a thread here safe. `mpv_wait_event` with a
 * finite deadline returns on its own whatever mpv does, so the shutdown path
 * cannot hang on a native call, and [stop] does not have to interrupt a thread
 * that is inside one.
 */
public class MpvEventPump(
    private val mpv: LibMpv,
    private val handle: MpvHandle,
    private val onEndFile: (reason: Int, error: Int) -> Unit,
) {

    @Volatile private var running: Boolean = false

    private val thread: Thread = Thread({ pump() }, "nomercy-mpv-events").apply { isDaemon = true }

    public fun start() {
        running = true
        thread.start()
    }

    /**
     * Stops reading and returns once the thread is out of the native call.
     *
     * Joined with a bound rather than indefinitely: a pump that will not stop
     * must not be able to hold up releasing the engine, because the thread is a
     * daemon and the handle underneath it is about to be destroyed either way.
     */
    public fun stop() {
        running = false
        mpv.mpv_wakeup(handle)
        thread.join(JOIN_MS)
    }

    // Nothing thrown here may escape: this is the last thread in the process
    // that would notice, and a pump that died silently takes every error report
    // with it -- which is the exact failure it was written to end.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pump() {
        while (running) {
            val keepGoing: Boolean = try {
                readOne()
            } catch (failure: RuntimeException) {
                false
            }
            if (!keepGoing) return
        }
    }

    /** False when mpv has shut down and there will be nothing more. */
    private fun readOne(): Boolean {
        val event: MpvEvent.ByReference = mpv.mpv_wait_event(handle, TIMEOUT_SECONDS) ?: return true
        if (event.eventId == MpvEventId.END_FILE) readEndFile(event)
        return event.eventId != MpvEventId.SHUTDOWN
    }

    private fun readEndFile(event: MpvEvent.ByReference) {
        val payload: MpvEndFile.ByReference = MpvEndFile.ByReference(event.data ?: return)
        payload.read()
        onEndFile(payload.reason, payload.error)
    }

    private companion object {
        // Long enough that an idle engine costs nothing, short enough that
        // stopping never waits on it noticeably.
        const val TIMEOUT_SECONDS: Double = 0.5
        const val JOIN_MS: Long = 2_000
    }
}
