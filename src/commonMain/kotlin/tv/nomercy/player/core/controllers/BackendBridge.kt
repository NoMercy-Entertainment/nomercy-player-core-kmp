// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.StreamError
import tv.nomercy.player.core.events.ProgressPayload
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.MediaBackend

private const val PERCENT = 100.0

// What the engine says, turned into what the player says.
//
// The controllers drive the engine; nothing was listening to it come back. That
// is the difference between `play` and `playing`: one is the action, the other
// is the engine confirming it actually started, and only the engine knows when.
// Without this bridge a chrome bound to `playing` shows a spinner forever while
// audio comes out of the speakers.
//
// Found by running the shared conformance scenarios against the native player:
// transport/play observed [beforePlay, phase, play] and stopped there.
public class BackendBridge(private val ctx: PlayerContext) {

    private val handlers: MutableList<Pair<String, (Any?) -> Unit>> = mutableListOf()

    private var announcedFirstFrame: Boolean = false

    public fun attach(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.LOADED_METADATA) {
            val duration: Double = backend.duration()
            if (duration > 0.0) {
                ctx.internalDuration = duration
                ctx.emit(CoreEvents.Duration, duration)
            }
        }

        listen(backend, CanonicalBackendEvent.PLAYING) {
            ctx.playState = PlayState.PLAYING
            ctx.bufferState = BufferState.IDLE
            if (ctx.phase == PlayerPhase.STARTING) ctx.transitionPhase(PlayerPhase.PLAYING)
            ctx.emit(CoreEvents.Playing, Unit)

            // Once per item, after the first frame is actually on screen. A
            // chrome waiting for mediaReady to take its poster down needs the
            // frame to exist first, or it swaps a still image for a black one.
            if (!announcedFirstFrame) {
                announcedFirstFrame = true
                ctx.emit(CoreEvents.FirstFrame, Unit)
                ctx.emit(CoreEvents.MediaReady, Unit)
            }
        }

        listen(backend, CanonicalBackendEvent.PAUSE) {
            ctx.playState = PlayState.PAUSED
        }

        attachStreamFailures(backend)

        listen(backend, CanonicalBackendEvent.LOAD_START) {
            ctx.bufferState = BufferState.LOADING
            // A new item has its own first frame.
            announcedFirstFrame = false
        }

        listen(backend, CanonicalBackendEvent.ENDED) {
            ctx.transitionPhase(PlayerPhase.ENDED)
            ctx.emit(CoreEvents.Ended, Unit)
        }

        attachPositionUpdates(backend)
        attachAdaptationTelemetry(backend)
    }

    // What the adaptive pipeline did, forwarded rather than interpreted.
    //
    // The payloads cross unchanged: an engine that knows a fragment's url and
    // duration knows them better than anything here could reconstruct, and a
    // bridge that rebuilt them would be a second, worse source of the same fact.
    // A malformed payload is dropped rather than guessed at — a fragment event
    // with no fragment tells a consumer nothing, and inventing an empty url
    // would put a lie in a support log.
    private fun attachAdaptationTelemetry(backend: MediaBackend) {
        forward(backend, CanonicalBackendEvent.MANIFEST_LOADED, CoreEvents.StreamManifestLoaded)
        forward(backend, CanonicalBackendEvent.FRAGMENT_LOADED, CoreEvents.StreamFragmentLoaded)
        forward(backend, CanonicalBackendEvent.LEVEL_CONSIDERED, CoreEvents.StreamLevelConsidered)
        forward(backend, CanonicalBackendEvent.LEVEL_SWITCHED, CoreEvents.StreamLevelSwitched)
    }

    private inline fun <reified T : Any> forward(backend: MediaBackend, event: String, key: EventKey<T>) {
        listen(backend, event) { payload ->
            (payload as? T)?.let { ctx.emit(key, it) }
        }
    }

    private fun attachPositionUpdates(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.TIME_UPDATE) {
            val time: Double = backend.currentTime()
            val duration: Double = backend.duration()
            ctx.internalCurrentTime = time
            if (duration > 0.0) ctx.internalDuration = duration
            val percentage: Double = if (duration <= 0.0) 0.0 else time / duration * PERCENT

            // Two events for one engine tick, in the web's order. progress is
            // what a scrubber binds to and time is what a clock binds to; they
            // carry the same numbers because they are the same moment, and a
            // consumer should not have to subscribe to both to get one.
            ctx.emit(CoreEvents.Progress, ProgressPayload(time, duration, percentage))
            ctx.emit(CoreEvents.Time, TimeUpdate(time = time, duration = duration, percentage = percentage))
        }
    }

    // Every subscription is remembered so detaching a backend really detaches
    // it. Swapping engines mid-session otherwise leaves the old one's listeners
    // emitting into a player that has moved on.
    public fun detach(backend: MediaBackend) {
        for ((event, handler) in handlers) backend.off(event, handler)
        handlers.clear()
    }

    // Hungry, and why.
    //
    // The same engine event means two different things to a viewer depending on
    // when it arrives. Before the first frame the source is still loading and a
    // spinner is expected; after playback started the buffer ran dry, and that
    // is worth saying out loud rather than showing the same spinner and hoping.
    private fun attachBufferState(backend: MediaBackend) {
        listen(backend, CanonicalBackendEvent.WAITING) {
            ctx.bufferState = if (announcedFirstFrame) BufferState.STALLED else BufferState.LOADING
        }

        listen(backend, CanonicalBackendEvent.STALLED) {
            ctx.bufferState = BufferState.STALLED
        }

        listen(backend, CanonicalBackendEvent.CAN_PLAY) {
            ctx.bufferState = BufferState.IDLE
        }
    }

    // The engine's stream failures, in the stream vocabulary rather than as a
    // generic error. A chrome that wants to say "the connection dropped" rather
    // than "playback failed" needs the distinction, and it is the engine that
    // knows which one happened.
    //
    // Fatal by default: an engine that reports a stream error and keeps playing
    // is reporting a recovered one, and it does not report at all when nothing
    // went wrong. Treating them as recoverable would have a chrome swallow the
    // case where playback actually stopped.
    private fun attachStreamFailures(backend: MediaBackend) {
        attachBufferState(backend)

        listen(backend, CanonicalBackendEvent.STREAM_ERROR) {
            ctx.emit(CoreEvents.StreamError, StreamError(details = it?.toString() ?: "", fatal = true))
        }
    }

    private fun listen(backend: MediaBackend, event: String, handler: (Any?) -> Unit) {
        backend.on(event, handler)
        handlers.add(event to handler)
    }
}
