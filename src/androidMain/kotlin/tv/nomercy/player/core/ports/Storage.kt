// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context

public actual fun defaultStorage(namespace: String): Storage = object : Storage {
    private val prefs = PlatformEnvironment.requireContext().androidContext
        .getSharedPreferences("nmplayer-" + namespace, Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = prefs.getString(key, null)

    override suspend fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
