// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin.fakes

import tv.nomercy.player.core.plugin.TransportCommands

// A player that records what the operating system asked it to do.
class RecordingTransportCommands : TransportCommands {

    val calls: MutableList<String> = mutableListOf()

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
    }

    override fun next() {
        calls += "next"
    }

    override fun previous() {
        calls += "previous"
    }

    override fun skipForward(offsetMs: Long) {
        calls += "skipForward:$offsetMs"
    }

    override fun skipBackward(offsetMs: Long) {
        calls += "skipBackward:$offsetMs"
    }
}
