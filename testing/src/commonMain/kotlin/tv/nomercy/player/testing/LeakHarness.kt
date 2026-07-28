// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

// Register, exercise, tear down, and check the bus is back where it started.
//
// The lifecycle is the caller's because how a subject is registered varies —
// a plugin goes through the registry, a chrome component subscribes directly,
// a consumer's view model does something else again. The harness owns the
// measurement, not the wiring.
//
// [exercise] is optional and matters more than it looks: a plugin that attaches
// its real listeners on first play registers nothing during setup, so a check
// that never drives it measures an idle object.
//
// Throws an AssertionError naming the subject and the numbers when the count
// did not come back. Returns them when it did, so a caller that wants to assert
// something sharper than "no leak" can.
public fun assertNoListenerLeak(
    subjectId: String,
    player: FakePlayer,
    setup: () -> Unit,
    exercise: () -> Unit = {},
    teardown: () -> Unit,
): LeakAssertionResult {
    val before: Int = player.listenerCount()

    setup()
    val afterSetup: Int = player.listenerCount()

    exercise()

    teardown()
    val afterTeardown: Int = player.listenerCount()

    val result = LeakAssertionResult(
        subjectId = subjectId,
        listenersBefore = before,
        listenersAfterSetup = afterSetup,
        listenersAfterTeardown = afterTeardown,
        leaked = afterTeardown - before,
    )

    if (result.leaked > 0) throw AssertionError(leakMessage(result))

    return result
}

// The same check, several times over.
//
// The leak worth finding is rarely the one that appears immediately. A handler
// attached on every third play, a cache that grows one entry per registration —
// both survive a single pass and neither survives this. Five is the default
// because it is enough to catch an every-other-time bug and cheap enough that
// nobody turns it off.
public fun assertNoListenerLeakOverCycles(
    subjectId: String,
    player: FakePlayer,
    setup: () -> Unit,
    exercise: () -> Unit = {},
    teardown: () -> Unit,
    cycles: Int = DEFAULT_LEAK_CYCLES,
): List<LeakAssertionResult> = (1..cycles).map { cycle ->
    assertNoListenerLeak(
        subjectId = "$subjectId#$cycle",
        player = player,
        setup = setup,
        exercise = exercise,
        teardown = teardown,
    )
}

public const val DEFAULT_LEAK_CYCLES: Int = 5

// The message is the whole deliverable here. A bare count tells an author
// something is wrong; naming the two calls that fix it tells them what to do,
// and that difference is what decides whether the harness gets used or muted.
private fun leakMessage(result: LeakAssertionResult): String =
    "[leak-harness] \"${result.subjectId}\" leaked ${result.leaked} listener(s). " +
        "before=${result.listenersBefore} " +
        "afterSetup=${result.listenersAfterSetup} " +
        "afterTeardown=${result.listenersAfterTeardown}. " +
        "Register listeners with this.on() or this.listen() inside the plugin, " +
        "or pair every raw player.on() with an off() in dispose()."
