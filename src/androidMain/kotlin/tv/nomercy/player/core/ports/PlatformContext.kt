// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context

// Always constructed with the application context, never an Activity: the
// player outlives any single screen, and holding an Activity here would leak it.
public actual class PlatformContext(public val androidContext: Context)
