// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.concurrent.Volatile

// Where the captured PlatformContext lives, so a port that needs one can ask
// instead of a consumer having to thread it through the API.
//
// On Android an androidx.startup initializer installs it before any player
// code runs, which is why setting up a player on Android takes no context
// argument. Platforms without a context never install and never ask.
//
// Volatile rather than a lock: this is written once at process start and read
// many times afterwards, so the only guarantee needed is that the write is
// visible to every thread that reads it.
public object PlatformEnvironment {
    @Volatile
    private var context: PlatformContext? = null

    public fun install(context: PlatformContext) {
        this.context = context
    }

    // Fails with the fix rather than with a null: the only way to reach this is
    // to have turned off the automatic capture, so the message says what to
    // call instead.
    public fun requireContext(): PlatformContext =
        context ?: error(
            "The player's platform context was never installed. On Android it is captured " +
                "automatically by androidx.startup; if that was disabled, call " +
                "PlatformEnvironment.install(...) before building a player.",
        )

    public fun isInstalled(): Boolean = context != null

    internal fun reset() {
        context = null
    }
}
