// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Anything that emits the minimal event set.
 *
 * The bridge is written against THIS rather than against a particular engine,
 * which is what lets one piece of lifecycle logic serve ExoPlayer, mpv, AVPlayer
 * and a fake in a test. Every engine added to this project so far arrived with
 * its own spelling of "started playing", and this is where that stops.
 */
public fun interface BackendLifecycleSource {
    public fun on(event: MinimalBackendEvent, handler: (MinimalBackendEventPayload) -> Unit)
}

/**
 * How the bridge turns raw engine events into player state.
 *
 * [onPlay] and [onPlayEvent] are separate and that is not redundancy. `play`
 * means the engine was ASKED to play; `playing` means frames are moving. A
 * player conflating them shows a spinner that vanishes before anything is on
 * screen, or never shows one at all.
 *
 * [pauseGuard] exists because engines pause themselves — for a seek, a track
 * change, a buffer — and publishing every one of those emits a pause the viewer
 * did not ask for and a chrome that flickers.
 *
 * [resetEvents] is per engine because what counts as "start over" is not
 * universal: some engines emit `loadstart` for a quality switch, and treating
 * that as a reset throws away the position mid-film.
 */
public data class BackendLifecycleBridgeOptions(
    val isPlaying: () -> Boolean,
    val setPlaying: (Boolean) -> Unit,

    /** The engine was asked to play. */
    val onPlay: () -> Unit,

    /** The engine's own `play` notification, when it needs separate handling. */
    val onPlayEvent: (() -> Unit)? = null,

    /** Frames are actually moving. */
    val onPlaying: () -> Unit,

    val onPause: () -> Unit,

    val onReset: () -> Unit,

    /** Return false to swallow a pause the engine raised for its own reasons. */
    val pauseGuard: (() -> Boolean)? = null,

    /** Which events mean this engine is starting over. */
    val resetEvents: List<MinimalBackendEvent> = listOf(MinimalBackendEvent.LOAD_START),
)
