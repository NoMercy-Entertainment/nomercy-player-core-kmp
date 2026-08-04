// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.ActionSource

// How loud, for the plugins that need to change it.
//
// Separate from [TransportCommands] rather than added to it, because that
// contract is what a lock screen may do to the player and a lock screen does
// not set the volume — the system mixer does. Widening it would hand every
// existing implementation a verb it has no business having.
public interface VolumeCommands {

    // 0..100, matching the player's own scale rather than introducing a second
    // one for a caller to convert into.
    public fun volume(level: Int)

    public fun mute()

    public fun unmute()
}

// [VolumeCommands] wired to the player, so a consumer registering a plugin that
// needs it writes one line rather than an adapter.
//
// Launched on the caller's scope and marked remote for the same reasons
// [PlayerTransportCommands] gives: the player's verbs suspend, the caller's do
// not, and something outside this player asked.
public open class PlayerVolumeCommands(
    private val player: ComposedPlayer,
    private val scope: CoroutineScope,
) : VolumeCommands {

    private val options = ActionOptions(source = ActionSource.REMOTE)

    override fun volume(level: Int) {
        scope.launch { player.volume(level, options) }
    }

    override fun mute() {
        scope.launch { player.mute(options) }
    }

    override fun unmute() {
        scope.launch { player.unmute(options) }
    }
}
