// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The same "none of them, said plainly" this desktop's SystemTransport
// chooses: Windows SMTC, Linux MPRIS and macOS's audio session are each a
// native binding of their own and none is built here yet. Focus is generally
// advisory on a desktop rather than exclusive in the way it is on a phone — a
// desktop plays and is not told to stop by another app wanting the speaker —
// so granting everything is closer to how these platforms actually behave
// than a Windows/Linux/macOS build silently inheriting a stub would suggest.
public actual fun defaultAudioFocusPort(): AudioFocusPort = AlwaysGrantedAudioFocus
