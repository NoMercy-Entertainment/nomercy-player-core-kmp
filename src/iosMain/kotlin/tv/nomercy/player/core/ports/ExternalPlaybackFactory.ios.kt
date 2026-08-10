// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.AVFoundation.AVPlayer

public actual fun makeExternalPlayback(player: AVPlayer): ExternalPlayback =
    AVPlayerExternalPlayback(player)
