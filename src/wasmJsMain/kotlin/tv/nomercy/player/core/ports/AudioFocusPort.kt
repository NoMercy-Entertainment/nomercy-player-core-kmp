// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// A browser tab has no audio-focus concept to bind to — the platform arbitrates
// between tabs, not between an app and this library — so there is nothing here
// to request or lose. Same reasoning as this target's SystemTransport.
public actual fun defaultAudioFocusPort(): AudioFocusPort = AlwaysGrantedAudioFocus
