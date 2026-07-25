// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Keeps the screen on while something is playing.
//
// [isSupported] exists because the honest answer on several platforms is no,
// and a chrome that offers a "keep screen on" control needs to know before it
// draws one.
public interface WakeLock {
    public suspend fun acquire()
    public suspend fun release()
    public fun isHeld(): Boolean
    public fun isSupported(): Boolean
}
