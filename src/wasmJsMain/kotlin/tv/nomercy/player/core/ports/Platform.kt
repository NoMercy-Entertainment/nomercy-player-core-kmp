// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Same conservative shared defaults jvmMain and appleMain fall back to. A
// receiver-specific wake-lock/network/visibility integration (the Screen Wake
// Lock API, navigator.onLine, the Page Visibility API) is a real follow-up,
// not invented here ahead of a caller that needs it.
public actual fun defaultPlatform(): Platform = object : Platform {
    override val wakeLock: WakeLock = NoopWakeLock
    override val network: NetworkMonitor = StaticNetworkMonitor()
    override val visibility: VisibilityMonitor = AlwaysVisible
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe
}
