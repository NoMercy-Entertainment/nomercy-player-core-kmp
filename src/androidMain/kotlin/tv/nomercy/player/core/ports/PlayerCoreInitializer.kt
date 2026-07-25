// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.startup.Initializer

// Captures the application context at process start.
//
// This is why building a player on Android takes no Context argument. The
// alternative is an init call every consumer has to remember, and the failure
// mode for forgetting it is a crash on first playback.
public class PlayerCoreInitializer : Initializer<PlatformContext> {
    override fun create(context: Context): PlatformContext {
        val platformContext = PlatformContext(context.applicationContext)
        PlatformEnvironment.install(platformContext)
        return platformContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
