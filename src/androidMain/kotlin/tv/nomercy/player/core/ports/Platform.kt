// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

public actual fun defaultPlatform(): Platform = object : Platform {
    override val wakeLock: WakeLock = NoopWakeLock
    override val network: NetworkMonitor =
        AndroidNetworkMonitor(PlatformEnvironment.requireContext().androidContext)
    override val visibility: VisibilityMonitor = AlwaysVisible
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe
}
