// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Empty, same as jvmMain and appleMain: a browser tab has no ambient context
// to capture. The type exists so the common seam has one shape everywhere.
public actual class PlatformContext
