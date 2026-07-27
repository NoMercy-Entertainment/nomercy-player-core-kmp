// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.pluginEventKey

// What the DRM plugin announces.
//
// Named keys rather than strings at the call sites, for the same reason every
// other event registry here is: a misspelled event is not an error, it is a
// listener that never fires.
// The names the plugin emits under. A plugin's emissions are namespaced on the
// way out, so these are what it publishes and the pair below is what a consumer
// subscribes to. Both are given rather than left to be spelled by hand, because
// a listener built from the wrong one is not an error, it is a listener that
// never fires.
public object DrmEvents {

    public val Unsupported: EventKey<DrmUnsupported> = EventKey("unsupported")

    public val KeyError: EventKey<DrmKeyError> = EventKey("key:error")

    public val UnsupportedOnPlayer: EventKey<DrmUnsupported> =
        pluginEventKey(DrmPlugin.Manifest, "unsupported")

    public val KeyErrorOnPlayer: EventKey<DrmKeyError> =
        pluginEventKey(DrmPlugin.Manifest, "key:error")
}

// Carries the scheme as well as the code, because a chrome offering another
// version of the film needs to know which one it must avoid.
public data class DrmUnsupported(
    val scheme: DrmScheme,
    val code: String,
)

public data class DrmKeyError(
    val sessionId: String,
    val code: String,
    val message: String,
)
