// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Not built yet. The port and the plugin land first so both can be proven
// against fakes, and each platform's real integration replaces this one file.
public actual fun defaultSystemTransport(): SystemTransport = NoSystemTransport()
