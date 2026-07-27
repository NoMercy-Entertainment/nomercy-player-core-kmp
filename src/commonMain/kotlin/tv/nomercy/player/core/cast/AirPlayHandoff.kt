// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cast

import tv.nomercy.player.core.ports.ExternalPlayback

// Sending playback to an AirPlay speaker or television.
//
// Deliberately not a CastSender, which is the seam for every other destination.
// That one pauses this device before handing the item and the position over,
// because two devices playing the same thing a second apart is what getting the
// order wrong sounds like. AirPlay is the opposite: the operating system moves
// the output of the player that is already running, so pausing first would stop
// the very playback about to be routed.
//
// It is also why nothing is handed over. There is no item to send and no
// position to resume from, because it is the same playback throughout. All this
// does is ask the platform to show its picker.
public class AirPlayHandoff(private val external: ExternalPlayback) {

    // Routed means the picker was shown, not that a speaker was chosen. Whether
    // anyone picks one is between the viewer and the platform, and the answer
    // arrives on the port's state rather than from here.
    public fun transfer(): HandoffResult {
        if (!external.isSupported) return HandoffResult.Unsupported(NO_ROUTE_SENDER)

        external.showRoutePicker()
        return HandoffResult.Routed
    }
}

public sealed interface HandoffResult {

    public data object Routed : HandoffResult

    // Carries why, because a chrome that only knows it failed can say nothing
    // useful, and "this device cannot send playback elsewhere" and "the licence
    // for this film forbids it" want different words on screen.
    public data class Unsupported(val reason: String) : HandoffResult
}

internal const val NO_ROUTE_SENDER = "this platform has no external route sender"
