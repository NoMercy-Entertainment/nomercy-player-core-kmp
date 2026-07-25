// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.Subscription

// Whether the surface the player draws into is on screen. A video player backs
// off decoding when it is not; a music player carries on.
public interface VisibilityMonitor {
    public fun isVisible(): Boolean
    public fun subscribe(fn: (Boolean) -> Unit): Subscription
}
