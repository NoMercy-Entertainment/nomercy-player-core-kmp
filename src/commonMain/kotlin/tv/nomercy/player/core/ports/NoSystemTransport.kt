// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// A platform whose system transport has not been built yet.
//
// Every method is empty and that is the accurate behaviour rather than a stub
// standing in for one: there is no lock screen to update, so updating it is
// nothing. A player using this still plays; what it does not do is appear on a
// notification shade or answer a steering-wheel button.
//
// It exists so the plugin can be written, tested and shipped against the port
// before any single platform's integration is finished — and so a platform
// gaining one is a change in one file rather than a change to the seam.
public open class NoSystemTransport : SystemTransport {

    override fun setNowPlaying(nowPlaying: NowPlaying): Unit = Unit

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ): Unit = Unit

    override fun setActionHandlers(actions: TransportActions): Unit = Unit

    override fun clear(): Unit = Unit

    override fun release(): Unit = Unit
}
