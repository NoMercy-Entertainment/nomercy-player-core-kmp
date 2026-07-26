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

// A platform that needs nothing installed.
//
// Not the same as defaultPlatform(): that one reaches for real monitors, and on
// Android reaching for them requires a Context the host has to have installed
// first. A player must be constructible before any of that has happened — in a
// test, in a headless tool, in a composable that runs before the app's
// initialiser — so this is what it uses until a host supplies better.
//
// Every answer here is the safe direction. Online rather than offline, because a
// player that assumed offline would refuse to start on every host that never
// wired a monitor up. Visible rather than hidden, because one that assumed
// hidden would pause itself for a host that never said otherwise. Both wrong
// guesses cost a request the error path already handles; the opposite guesses
// cost a player that does nothing and cannot say why.
public object UnconfiguredPlatform : Platform {
    override val wakeLock: WakeLock = NoopWakeLock
    override val network: NetworkMonitor = StaticNetworkMonitor()
    override val visibility: VisibilityMonitor = AlwaysVisible
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe
}
