// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import tv.nomercy.player.core.plugin.Plugin

// One call that stands a plugin up, hands it to you, takes it down, and checks
// it left nothing behind.
//
//     @Test fun theLyricsPluginPaints() = runTest {
//         testPlugin(LyricsPlugin()) { player, plugin ->
//             player.emit(CoreEvents.Time, TimeUpdate(12.0, 240.0))
//             assertEquals("the line at twelve seconds", plugin.current)
//         }
//     }
//
// The registration is the real one: the registry validates the manifest, wires
// the plugin's scoped logger and storage, and runs `use()`. What the test drives
// afterwards runs through the same emitter the plugin subscribed to.
//
// Teardown is the point as much as setup. Every test written this way is also a
// leak test, at no cost to the author, which is the only way a leak check gets
// run on the plugins that need it rather than on the ones somebody remembered.
//
// [player] is a parameter so a plugin needing arranged ports — a queued fetch
// response, seeded storage, a socket — is testable with the same call. It also
// covers the case a plugin holds the player it was given.
public fun <O : Any, P : Plugin<O>> testPlugin(
    plugin: P,
    player: FakePlayer = FakePlayer(),
    options: O? = null,
    exercise: (player: FakePlayer, plugin: P) -> Unit = { _, _ -> },
): LeakAssertionResult = assertNoListenerLeak(
    subjectId = plugin.id,
    player = player,
    setup = { player.plugins.register(plugin, options) },
    exercise = { exercise(player, plugin) },
    teardown = { player.plugins.remove(plugin.id) },
)
