// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.util.UnstableApi

// One Media3 session, built from the context the library was installed with.
//
// Falls back to doing nothing rather than throwing when no context has been
// installed. That is a host JVM test, or a process using the core without the
// Android initializer, and neither is a reason for a player to refuse to start
// — it is a reason for it not to appear on a lock screen it cannot reach.
@UnstableApi
public actual fun defaultSystemTransport(): SystemTransport =
    if (PlatformEnvironment.isInstalled()) {
        Media3SystemTransport(PlatformEnvironment.requireContext().androidContext)
    } else {
        NoSystemTransport()
    }
