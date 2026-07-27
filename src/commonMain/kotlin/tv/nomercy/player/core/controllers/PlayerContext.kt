// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.stateError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventEmitter
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PhaseChange
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.MediaList
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState
import tv.nomercy.player.core.player.VolumeState
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.MediaBackend

private const val FULL_VOLUME = 100

// The one thing every controller reads and writes.
//
// Controllers own no state of their own. Transport, volume, time and queue all
// mutate this object, which is why a volume change made from the transport path
// and one made from a plugin cannot disagree — there is only one copy.
//
// It is deliberately a bag of open fields rather than a set of accessors. The
// controllers are its API; wrapping every field in a getter and setter would add
// a layer whose only job is to be passed through.
@Suppress("TooManyFunctions")
public class PlayerContext(
    public val emitter: EventEmitter<Unit> = EventEmitter(),
    public var backend: MediaBackend? = null,
) {
    public fun <T> emit(key: EventKey<T>, data: T): Unit = emitter.emit(key, data)

    public fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = emitter.on(key, fn)

    public suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = emitter.dispatchBefore(key, data)

    public fun dispatching(): List<String> = emitter.dispatching()

    // The single writer of phase. Everything that changes it goes through
    // transitionPhase, so the phase event cannot be missed by a caller who
    // assigned the field directly.
    public var phase: PlayerPhase = PlayerPhase.IDLE
        private set

    public fun transitionPhase(next: PlayerPhase) {
        val from: PlayerPhase = phase
        if (from == next) return
        phase = next
        emit(CoreEvents.Phase, PhaseChange(from, next))
    }

    // Refuses the two states where a transport call cannot mean anything, and
    // says which one it was: "not set up yet" and "already disposed" are
    // different bugs in the calling code.
    public fun assertReady() {
        if (phase == PlayerPhase.IDLE) {
            throw stateError(CoreErrorCodes.NOT_READY, "The player has not been set up yet.")
        }
        if (phase == PlayerPhase.DISPOSING || phase == PlayerPhase.DISPOSED) {
            throw stateError(CoreErrorCodes.DISPOSED, "The player has been disposed.")
        }
    }

    // Why the picture is not moving, when it is not moving.
    //
    // A chrome shows different things for these: a spinner over a black frame
    // while a source loads, a spinner over the last frame while a seek lands,
    // and — the one that matters — a stall badge when the buffer ran dry
    // mid-playback, which is the difference between "wait a moment" and "your
    // connection is not keeping up".
    public var bufferState: BufferState = BufferState.IDLE

    public var playState: PlayState = PlayState.IDLE

    // 0..100, the public scale. The backend's own is 0..1 and the conversion
    // happens once, at the call into it.
    public var volumeState: VolumeState = VolumeState.UNMUTED
    public var internalVolume: Int = FULL_VOLUME
    public var volumeBeforeMute: Int = FULL_VOLUME

    public var internalCurrentTime: Double = 0.0
    public var internalDuration: Double = 0.0
    public var playbackRate: Double = 1.0

    // Latches so itemEndingSoon fires once per item rather than on every time
    // update inside the window. Cleared by load().
    public var itemEndingSoonEmitted: Boolean = false

    public val queue: MediaList<PlaylistItem> = MediaList()

    // What has already played, for a back button that works after a shuffle
    // reordered the queue underneath it.
    public val backlog: MediaList<PlaylistItem> = MediaList()

    public var queueWired: Boolean = false

    public var repeatState: RepeatState = RepeatState.OFF
    public var shuffleState: ShuffleState = ShuffleState.OFF

    public var auth: AuthController? = null

    // The one path from an item to the engine. Every transport route ends up
    // here, so authorisation and the ending-soon latch are handled once instead
    // of at each call site.
    // Which item the engine currently holds, so play() can tell an engine
    // that has something loaded from one that has nothing.
    public var loadedItemId: String? = null
        private set

    // The load has settled by the time this returns: the engine holds the source
    // and will accept a seek. That is what anything continuing a load waits for
    // — hiding a poster, restoring a saved position, or reconciling to another
    // device's position before starting.
    //
    // It used to be announced from the first-frame branch instead, which reads
    // plausibly and is a deadlock: a caller waiting for it before playing waits
    // for something that only happens once playing has already started.
    public suspend fun load(item: PlaylistItem, opts: LoadOptions = LoadOptions()) {
        loadQuietly(item, opts)
        emit(CoreEvents.MediaReady, Unit)
    }

    // The same load with the announcement left to the caller.
    //
    // For the one caller that has to announce later: starting playback on an
    // empty player loads first, and in the reference the engine is kicked while
    // that load is still settling — so readiness is reported after playback has
    // begun, not before it. Announcing from here would put it first and change
    // an ordering a chrome can see.
    internal suspend fun loadQuietly(item: PlaylistItem, opts: LoadOptions = LoadOptions()) {
        val url: String = auth?.transformUrl(item.url) ?: item.url
        backend?.load(url, opts)
        loadedItemId = item.id
        itemEndingSoonEmitted = false
    }
}
