// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// libVLC's notifications, moved off libVLC's thread before anybody hears them.
//
// The move is the point, not a nicety. libVLC raises its events from inside its
// own decoder and input threads, and a listener above this binding will call
// straight back into the engine — a controller that hears "playing" and asks for
// the current time is the ordinary case. Calling into libVLC from inside its own
// callback deadlocks it.
//
// So the payload is read here, on the native thread, where the event struct is
// still alive, and the delivery happens on one thread of our own. ONE thread:
// the events arrive in an order that means something — playing then time then
// paused — and a pool would reorder them.
internal class VlcEventSpine(
    private val binding: LibVlcBinding,
    playerHandle: Pointer,
    private val listener: VlcPlayerEvents,
) {

    private val manager: Pointer? = binding.events.mediaPlayerEventManager(playerHandle)

    private val deliveries: ExecutorService = Executors.newSingleThreadExecutor { work ->
        // A daemon, because a player nobody released must not be what keeps the
        // JVM from exiting — which is exactly how a hung test task presents.
        Thread(work, "nomercy-libvlc-events").apply { isDaemon = true }
    }

    // Held for as long as libVLC can call it. JNA keeps no reference of its own,
    // so a callback that became unreachable here would be collected while the
    // native side still had its address — a crash rather than a missed event.
    private val callback = VlcEventCallback { event, _ -> receive(event) }

    init {
        manager?.let { attached ->
            ATTACHED.forEach { type -> binding.events.eventAttach(attached, type, callback, null) }
        }
    }

    // Detached before the player is, and that order is not negotiable: libVLC
    // can be mid-callback when release is called, and detaching first is what
    // makes it wait for one that is in flight.
    fun release() {
        manager?.let { attached ->
            ATTACHED.forEach { type -> binding.events.eventDetach(attached, type, callback, null) }
        }
        deliveries.shutdown()
    }

    // Both payloads, read unconditionally. They are two views of the same eight
    // bytes and neither read can fault, so branching first would buy nothing and
    // add a place for the union to be read after the struct was freed.
    private fun receive(event: Pointer) {
        val type: Int = event.getInt(0)
        if (type !in ATTACHED) return

        val millis: Long = event.getLong(PAYLOAD_OFFSET)
        val cache: Float = event.getFloat(PAYLOAD_OFFSET)
        runCatching { deliveries.execute { deliver(type, millis, cache) } }
    }

    private fun deliver(type: Int, millis: Long, cache: Float) {
        when (type) {
            PLAYING -> listener.playing()
            PAUSED -> listener.paused()
            END_REACHED -> listener.ended()
            ENCOUNTERED_ERROR -> listener.errored()
            TIME_CHANGED -> listener.timeChanged(millis)
            LENGTH_CHANGED -> listener.lengthChanged(millis)
            BUFFERING -> listener.buffering(cache)
        }
    }

    private companion object {
        // libvlc_event_e, from vlc/libvlc_events.h. The media player's own block
        // starts at 0x100 and these are its members by name.
        const val BUFFERING: Int = 259
        const val PLAYING: Int = 260
        const val PAUSED: Int = 261
        const val END_REACHED: Int = 265
        const val ENCOUNTERED_ERROR: Int = 266
        const val TIME_CHANGED: Int = 267
        const val LENGTH_CHANGED: Int = 273

        val ATTACHED: List<Int> = listOf(
            BUFFERING,
            PLAYING,
            PAUSED,
            END_REACHED,
            ENCOUNTERED_ERROR,
            TIME_CHANGED,
            LENGTH_CHANGED,
        )

        // Where the union sits inside libvlc_event_t, which is `int type` then
        // `void *p_obj` then the union — so one pointer's worth of padded int
        // plus one pointer, on every architecture this ships to.
        val PAYLOAD_OFFSET: Long = Native.POINTER_SIZE.toLong() * 2
    }
}
