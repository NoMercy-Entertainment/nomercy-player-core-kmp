// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One playback handle, as a crossfade sees it.
//
// A crossfade needs two things playing at once and a gain on each. That is the
// whole of it — no seeking, no queue, no track selection — and keeping the
// interface to exactly that is what lets one crossfade implementation drive two
// ExoPlayers, two AVPlayers or two libVLC players without knowing which.
//
// Every engine that can play two things at once can satisfy this. An engine that
// cannot is one that cannot crossfade, and the narrow surface makes that
// obvious rather than discovering it halfway through an implementation.
public interface GainSink {

    public fun gain(): Float

    public fun gain(value: Float)

    public suspend fun play()

    // Called once, when the fade is over and this handle is the one that faded
    // out. Separate from gain(0) because a silent player still holds a decoder,
    // and two of those per transition is how a queue runs a device out of them.
    public fun releaseAfterFade()
}
