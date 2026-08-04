// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.leader

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.pluginEventKey

// What the leader plugin announces.
//
// The names it emits under and the names a consumer subscribes to, both given
// rather than spelled by hand, because a listener built from the wrong one is
// not an error, it is a listener that never fires.
public object TabLeaderEvents {

    public val LeaderAcquired: EventKey<Unit> = EventKey("leader-acquired")

    public val LeaderReleased: EventKey<Unit> = EventKey("leader-released")

    public val Unsupported: EventKey<Unit> = EventKey("unsupported")

    public val LeaderAcquiredOnPlayer: EventKey<Unit> =
        pluginEventKey(TabLeaderPlugin.Manifest, "leader-acquired")

    public val LeaderReleasedOnPlayer: EventKey<Unit> =
        pluginEventKey(TabLeaderPlugin.Manifest, "leader-released")

    public val UnsupportedOnPlayer: EventKey<Unit> =
        pluginEventKey(TabLeaderPlugin.Manifest, "unsupported")
}
