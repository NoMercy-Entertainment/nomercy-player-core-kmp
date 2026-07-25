// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The one platform handle the library needs, and the only place it exists.
//
// Android wraps android.content.Context because preferences and system services
// require it. Nothing else has an equivalent, so those actuals are empty.
//
// It deliberately declares no constructor here: common code cannot build one,
// which is what keeps it out of every common signature. It reaches the ports
// that need it through PlatformEnvironment.
public expect class PlatformContext
