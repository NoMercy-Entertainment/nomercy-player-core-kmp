// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.AVFoundation.AVPlayer

// One AVPlayer-backed video engine, compiled once for every Apple target —
// and two different answers for what it can route externally. iOS gets the
// real AirPlay sender; tvOS has no route picker to hand back, because AirPlay
// there is Control Center's job, not an app's.
public expect fun makeExternalPlayback(player: AVPlayer): ExternalPlayback
