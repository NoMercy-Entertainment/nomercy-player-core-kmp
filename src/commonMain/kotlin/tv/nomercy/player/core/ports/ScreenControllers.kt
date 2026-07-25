// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.Subscription

// Video-only. Both are absent on the music player and on platforms that do not
// offer them, which is why Platform holds them as nullable rather than making
// every implementation write no-ops that lie.

public interface FullscreenController {
    public suspend fun enter()
    public suspend fun exit()
    public fun isActive(): Boolean
    public fun isSupported(): Boolean

    // A viewer can leave fullscreen without going through the player — an OS
    // gesture, the escape key — so the state is observed, never assumed.
    public fun subscribe(fn: (Boolean) -> Unit): Subscription
}

public interface PipController {
    public suspend fun enter()
    public suspend fun exit()
    public fun isActive(): Boolean
    public fun isSupported(): Boolean
    public fun subscribe(fn: (Boolean) -> Unit): Subscription
}
