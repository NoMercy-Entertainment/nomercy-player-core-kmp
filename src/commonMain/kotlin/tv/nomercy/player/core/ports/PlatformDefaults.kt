// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.Subscription

// Conservative stand-ins so every target has a working Platform from day one.
// Each reports honestly about what it does not do rather than pretending, and
// the media-session plan replaces them with the real thing.

public object NoopWakeLock : WakeLock {
    override suspend fun acquire(): Unit = Unit
    override suspend fun release(): Unit = Unit
    override fun isHeld(): Boolean = false

    // False, not true-with-no-effect: a chrome that trusts this will not draw a
    // control that does nothing.
    override fun isSupported(): Boolean = false
}

// For platforms whose reachability API costs more than it is worth right now.
// Reports UNKNOWN rather than guessing WIFI, so ABR treats the reading as no
// information instead of as a fast link.
public class StaticNetworkMonitor(
    private val online: Boolean = true,
    private val kind: NetworkType = NetworkType.UNKNOWN,
) : NetworkMonitor {
    override fun isOnline(): Boolean = online
    override fun type(): NetworkType = kind
    override fun downlinkMbps(): Double? = null
    override fun rttMs(): Double? = null
    override fun subscribe(fn: (NetworkSnapshot) -> Unit): Subscription = Subscription {}
}

public object AlwaysVisible : VisibilityMonitor {
    override fun isVisible(): Boolean = true
    override fun subscribe(fn: (Boolean) -> Unit): Subscription = Subscription {}
}

// Says yes to everything, which is safe only because the backend's own decode
// attempt is the real gate: a wrong yes costs one failed load, a wrong no makes
// a playable rendition permanently invisible.
public object PermissiveCapabilitiesProbe : CapabilitiesProbe {
    override suspend fun canDecode(profile: DecodeProfile): DecodeCapability =
        DecodeCapability(supported = true, smooth = true, powerEfficient = false)

    override suspend fun supportedCodecs(): List<String> = emptyList()
}
