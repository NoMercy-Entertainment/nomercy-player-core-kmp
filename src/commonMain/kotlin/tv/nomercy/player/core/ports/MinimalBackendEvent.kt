// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * The thirteen notifications every media engine produces, whatever it is.
 *
 * An enum rather than strings because these are what the lifecycle bridge
 * switches on, and a typo in one is a player that never learns it started
 * playing — no error, no log, just a play button that stays a play button while
 * sound comes out of the speakers.
 */
public enum class MinimalBackendEvent(public val id: String) {
    LOAD_START("loadstart"),
    LOADED_METADATA("loadedmetadata"),
    CAN_PLAY("canplay"),
    PLAY("play"),
    PLAYING("playing"),
    PAUSE("pause"),
    ENDED("ended"),
    TIME_UPDATE("timeupdate"),
    WAITING("waiting"),
    STALLED("stalled"),
    RATE_CHANGE("ratechange"),
    ENCRYPTED("encrypted"),
    ERROR("error"),
}

/** One of those notifications, with whatever the engine attached to it. */
public data class MinimalBackendEventPayload(
    val event: MinimalBackendEvent,
    val data: Any? = null,
)
