// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.example.watchcount

import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.devtools.PlayerInspector
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.testing.FakePlayer

// The "inspector shows the live event stream" half of the anti-hollow proof.
// `PlayerInspector` takes anything that is an `EventFirehose` — `FakePlayer`
// is one, the same as the real player — so attaching it here is exactly what a
// consumer does against production. What is standing in for the real engine is
// the sequence of transport events themselves: a real `NMVideoPlayer` fires
// `beforePlay` → `play` → `playing` from its `TransportController` when
// `play()` is called; this demo dispatches that same sequence directly on the
// shared event bus `FakePlayer` exposes, since core alone (headless, no
// concrete player) has no `play()` to call — only `nomercy-video-player-kmp`/
// `nomercy-music-player-kmp` assemble one. The inspector cannot tell the
// difference: it only ever sees events on the bus, never who dispatched them.
fun main(): Unit = runBlocking {
    val player = FakePlayer()
    val inspector = PlayerInspector(player)

    // beforePlay/beforePause never reach the inspector — proven by running
    // this, not assumed from the plan that first described this demo.
    // `dispatchBefore` resolves a permission gate through `runListeners`,
    // which notifies only typed `on(key)` subscribers; the firehose is fed by
    // `deliver()`, the path only `emit()` takes. A before-hook is a decision
    // still being made, not yet a thing that happened, and `PlayerInspector`
    // — "what happened just before the thing that went wrong" — is honest to
    // exclude it: a gate a plugin could still refuse is not settled history.
    player.dispatchBefore(CoreEvents.BeforePlay, ActionOptions())
    player.emit(CoreEvents.Play, PlaySource("user"))
    player.emit(CoreEvents.Playing, Unit)

    player.dispatchBefore(CoreEvents.BeforePause, ActionOptions())
    player.emit(CoreEvents.Pause, PlaySource("user"))

    val names: List<String> = inspector.events.value.map { it.name }
    check(names == listOf("play", "playing", "pause")) {
        "expected the real play->pause order the firehose actually captures, saw: $names"
    }

    println("PlayerInspector captured: $names")
    inspector.dispose()
}
