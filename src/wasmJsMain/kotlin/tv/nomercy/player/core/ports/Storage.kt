// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

// window.localStorage has one flat namespace per origin, so the prefix is the
// separation a suite name would otherwise give — same shape as appleMain's
// NSUserDefaults actual.
@JsFun("(key) => window.localStorage.getItem(key)")
private external fun jsLocalStorageGet(key: JsString): JsString?

@JsFun("(key, value) => window.localStorage.setItem(key, value)")
private external fun jsLocalStorageSet(key: JsString, value: JsString)

@JsFun("(key) => window.localStorage.removeItem(key)")
private external fun jsLocalStorageRemove(key: JsString)

public actual fun defaultStorage(namespace: String): Storage = object : Storage {
    private val prefix: String = "nmplayer-$namespace-"

    override suspend fun get(key: String): String? =
        jsLocalStorageGet((prefix + key).toJsString())?.toString()

    override suspend fun set(key: String, value: String) {
        jsLocalStorageSet((prefix + key).toJsString(), value.toJsString())
    }

    override suspend fun remove(key: String) {
        jsLocalStorageRemove((prefix + key).toJsString())
    }
}
