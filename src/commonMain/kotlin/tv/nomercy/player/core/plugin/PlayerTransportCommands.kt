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

// The lock screen's six buttons, wired to the player.
//
// Two mismatches to cross, and both are why this exists rather than the plugin
// holding a player directly.
//
// The first is threading. A transport button arrives on whichever thread the
// operating system felt like — a Media3 callback, a remote command handler, a
// D-Bus dispatch — and the player's surface suspends. So each verb is launched
// on the scope the caller owns, which is the player's own scope in practice:
// a command outliving the player it drives is a crash on a released engine.
//
// The second is unit. The system speaks milliseconds and the player speaks
// seconds, and the conversion happens here so neither side has to know about
// the other's choice.
//
// An absolute position goes through time(seconds), which is the player's own
// scrub surface and the one the web contract names. A seek(seconds) was added
// to the player for this and taken straight back out: the conformance gate
// pointed out that the web player has no such method, and a core that invents
// one is a difference a consumer meets as "it works on the other platform".
public open class PlayerTransportCommands(
    private val player: ComposedPlayer,
    private val scope: CoroutineScope,
) : TransportCommands {

    // Marked as remote rather than as a user action, because from the player's
    // point of view that is what it is: something outside this process asked.
    // A listener that treats "the viewer pressed pause" and "the car pressed
    // pause" identically can, and one that needs to tell them apart now can.
    private val options = ActionOptions(source = ActionSource.REMOTE)

    override fun play() {
        scope.launch { player.play(options) }
    }

    override fun pause() {
        scope.launch { player.pause(options) }
    }

    override fun stop() {
        scope.launch { player.stop(options) }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch { player.time(positionMs / MILLIS_PER_SECOND, options) }
    }

    override fun next() {
        scope.launch { player.next(options) }
    }

    override fun previous() {
        scope.launch { player.previous(options) }
    }

    // forward and rewind rather than time(now +/- offset), because that is what
    // the web plugin's seekforward and seekbackward handlers call and the two
    // are not the same thing: the player's own skip clamps at the ends and
    // announces itself as a skip, and a computed absolute position does neither.
    override fun skipForward(offsetMs: Long) {
        scope.launch { player.forward(offsetMs / MILLIS_PER_SECOND, options) }
    }

    override fun skipBackward(offsetMs: Long) {
        scope.launch { player.rewind(offsetMs / MILLIS_PER_SECOND, options) }
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
