// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.pluginEventKey

/**
 * The names the cast sender emits under, and the names a consumer subscribes to.
 *
 * Both given rather than spelled by hand, because a listener built from the
 * wrong one is not an error — it is a listener that never fires, and nobody
 * finds out until a television in another room fails to appear in a menu.
 */
public object CastSenderEventKeys {

    public val Connected: EventKey<CastSenderEvents.Connected> = EventKey("cast:connected")

    public val Disconnected: EventKey<Unit> = EventKey("cast:disconnected")

    public val Failed: EventKey<CastSenderEvents.Failed> = EventKey("cast:error")

    public val RemoteState: EventKey<CastSenderEvents.RemoteState> = EventKey("cast:remote-state")

    public val MediaChanged: EventKey<CastSenderEvents.MediaChanged> = EventKey("cast:media-changed")

    public val Unsupported: EventKey<CastSenderEvents.Unsupported> = EventKey("unsupported")

    public val ConnectedOnPlayer: EventKey<CastSenderEvents.Connected> =
        pluginEventKey(CastSenderPlugin.Manifest, "cast:connected")

    public val DisconnectedOnPlayer: EventKey<Unit> =
        pluginEventKey(CastSenderPlugin.Manifest, "cast:disconnected")

    public val FailedOnPlayer: EventKey<CastSenderEvents.Failed> =
        pluginEventKey(CastSenderPlugin.Manifest, "cast:error")

    public val RemoteStateOnPlayer: EventKey<CastSenderEvents.RemoteState> =
        pluginEventKey(CastSenderPlugin.Manifest, "cast:remote-state")

    public val MediaChangedOnPlayer: EventKey<CastSenderEvents.MediaChanged> =
        pluginEventKey(CastSenderPlugin.Manifest, "cast:media-changed")

    public val UnsupportedOnPlayer: EventKey<CastSenderEvents.Unsupported> =
        pluginEventKey(CastSenderPlugin.Manifest, "unsupported")
}
