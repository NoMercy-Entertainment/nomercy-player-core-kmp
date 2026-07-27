// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// Nothing. A window shows exactly the pixels it was given, and a desktop player
// insetting its controls would be leaving a border around a picture that has no
// edge problem to solve.
public actual fun platformOverscan(context: PlatformContext): SafeAreaInsets = SafeAreaInsets()
