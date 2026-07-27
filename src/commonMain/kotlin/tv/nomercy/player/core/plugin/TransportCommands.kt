// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// What a system transport is allowed to do to the player.
//
// The whole player is not on offer. A lock screen needs six verbs and a plugin
// holding a player reference could reach any of them — which is how an OS
// integration acquires the ability to change a subtitle track, and why the web
// player's own media-session plugin talks through a bridge rather than through
// the player object it happens to have.
//
// The concrete player implements this. It is the same contract-based dependency
// injection the rest of the ports use: one interface, one dispatcher, no lookup
// by string.
public interface TransportCommands {

    public fun play()

    public fun pause()

    public fun stop()

    // Milliseconds, matching the port. The player converts to its own unit once,
    // where it already knows what that unit is.
    public fun seekTo(positionMs: Long)

    // Queue movement is optional because not every player has a queue. A video
    // player showing one film has no next, and the transport hides the button
    // rather than showing one that does nothing.
    public fun next(): Unit = Unit

    public fun previous(): Unit = Unit
}
