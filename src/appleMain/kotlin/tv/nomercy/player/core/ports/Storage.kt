// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.Foundation.NSUserDefaults

public actual fun defaultStorage(namespace: String): Storage = object : Storage {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    // NSUserDefaults has one flat namespace per app, so the prefix is the
    // separation a suite name would otherwise give.
    private val prefix: String = "nmplayer-" + namespace + "-"

    override suspend fun get(key: String): String? = defaults.stringForKey(prefix + key)

    override suspend fun set(key: String, value: String) {
        defaults.setObject(value, prefix + key)
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(prefix + key)
    }
}
