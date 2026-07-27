// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Straight cinterop rather than a Swift bridge.
//
// That was an open question with a fallback plan behind it: MPRemoteCommandCenter
// hands out its targets through blocks returning a status, and
// MPNowPlayingInfoCenter wants a heterogeneous dictionary keyed by framework
// constants — neither obviously bridgeable from Kotlin, and the app already has
// Swift that does both. Both bridge without help, so the Swift is not needed and
// there is no second implementation to keep in step with this one.
public actual fun defaultSystemTransport(): SystemTransport = AppleSystemTransport()
