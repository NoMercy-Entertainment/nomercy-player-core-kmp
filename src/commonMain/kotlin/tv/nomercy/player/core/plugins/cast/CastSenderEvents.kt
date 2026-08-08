// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * What the cast sender reports.
 *
 * [RemoteState] carries the position because the receiver becomes the clock the
 * moment casting starts: the local player is paused and its own time is frozen
 * at whatever it was when the session began, so a chrome reading the local clock
 * shows a stopped timer over a film that is playing across the room.
 */
public sealed interface CastSenderEvents {

    public data class Connected(val deviceName: String) : CastSenderEvents

    public data object Disconnected : CastSenderEvents

    public data class Failed(val error: Throwable) : CastSenderEvents

    public data class RemoteState(
        val time: Double,
        val state: CastPlaybackState,
    ) : CastSenderEvents

    public data class MediaChanged(val contentId: String) : CastSenderEvents

    /**
     * Casting cannot work here, and why.
     *
     * A reason rather than a bare flag: no receiver framework, no network
     * permission, and a device that has simply never seen a receiver are three
     * different things, and a chrome hiding the button for all three tells the
     * viewer nothing about which.
     */
    public data class Unsupported(val reason: String) : CastSenderEvents
}

public enum class CastPlaybackState(public val id: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
}
