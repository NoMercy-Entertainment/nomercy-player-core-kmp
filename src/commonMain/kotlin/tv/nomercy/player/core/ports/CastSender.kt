// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.CastTarget
import tv.nomercy.player.core.media.PlaylistItem

// Hands playback to something else in the room.
//
// A port because core cannot cast: the protocols are a Google SDK, an AirPlay
// route, and NoMercy's own Connect session, and none of them belong in a library
// whose job is deciding what plays. Whatever owns the session implements this —
// today that is a plugin.
//
// What core does own is the choreography: ask permission, stop locally, hand
// over the item and the position, report the state. Doing that here is what
// makes a handoff behave the same whether it lands on a Chromecast, an Apple TV
// or another NoMercy client.
public interface CastSender {
    // [position] in seconds, so the far end resumes where the viewer was rather
    // than at the top. A handoff that restarts the episode is the single thing
    // that makes people stop using the feature.
    public suspend fun transfer(target: CastTarget, item: PlaylistItem?, position: Double): Boolean

    // Pull playback back to this device. Returns where the remote had got to, so
    // the local engine resumes from there instead of from where it was paused
    // when the session started.
    public suspend fun reclaim(): Double?
}
