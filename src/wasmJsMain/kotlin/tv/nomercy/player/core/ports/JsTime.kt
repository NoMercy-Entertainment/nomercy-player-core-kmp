// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

// Kotlin/Wasm has no kotlin.system.getTimeMillis and no java.util.UUID —
// both come straight off the browser, the same pattern cast-web's own
// CastSessionAuth.kt already uses for `Date.now()`.
@JsFun("() => Date.now()")
internal external fun jsNowMillis(): Double

@JsFun("() => crypto.randomUUID()")
internal external fun jsRandomUuid(): JsString

public actual fun defaultClock(): Clock = object : Clock {
    override fun now(): Long = jsNowMillis().toLong()
}

public actual fun defaultIdGenerator(): IdGenerator = object : IdGenerator {
    override fun next(): String = jsRandomUuid().toString()
}
