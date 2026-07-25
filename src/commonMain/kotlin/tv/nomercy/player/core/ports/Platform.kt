// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The host platform, as far as the player needs to know it.
//
// Four things every platform has and two only some do. Fullscreen and
// picture-in-picture are nullable rather than universally-implemented no-ops,
// so a chrome asking for them gets an honest absent instead of a control that
// silently does nothing.
public interface Platform {
    public val wakeLock: WakeLock
    public val network: NetworkMonitor
    public val visibility: VisibilityMonitor
    public val capabilities: CapabilitiesProbe

    public val fullscreen: FullscreenController? get() = null
    public val pip: PipController? get() = null
}

public expect fun defaultPlatform(): Platform
