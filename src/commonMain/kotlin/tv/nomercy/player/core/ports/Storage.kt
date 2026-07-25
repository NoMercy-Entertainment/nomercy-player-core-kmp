// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Key-value persistence, whatever the platform calls it. A plugin's storage is
// key-prefixed with its own id before it reaches here, so two plugins that both
// store "enabled" do not overwrite each other.
public interface Storage {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}
