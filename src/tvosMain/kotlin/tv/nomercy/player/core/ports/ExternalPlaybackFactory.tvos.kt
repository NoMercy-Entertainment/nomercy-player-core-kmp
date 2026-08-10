// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.AVFoundation.AVPlayer

// tvOS has no AVRoutePickerView — AirPlay is Control Center's job there, not
// an app's — so there is no route sender for this port to hand back.
public actual fun makeExternalPlayback(player: AVPlayer): ExternalPlayback =
    UnsupportedExternalPlayback
