// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Where a CustomTransportButton's iconKey becomes an actual Android drawable
// resource id. Installed once, read many times — the same seam
// MediaNotificationBranding already uses for its own icon, not a second
// holder beside it. A button whose key resolves to nothing draws with
// CommandButton's own undefined icon rather than a resource this library
// invented.
public fun interface CustomTransportButtonIconResolver {
    public fun resolve(iconKey: String): Int
}

@Volatile
private var installedCustomTransportButtonIconResolver: CustomTransportButtonIconResolver? = null

public fun PlatformEnvironment.installCustomTransportButtonIconResolver(
    resolver: CustomTransportButtonIconResolver,
) {
    installedCustomTransportButtonIconResolver = resolver
}

public val PlatformEnvironment.customTransportButtonIconResolver: CustomTransportButtonIconResolver?
    get() = installedCustomTransportButtonIconResolver
