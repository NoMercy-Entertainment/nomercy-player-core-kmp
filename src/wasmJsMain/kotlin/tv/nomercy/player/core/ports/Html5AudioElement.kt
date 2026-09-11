// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

// The DOM surface the browser audio engine drives, written as external JsAny
// interfaces rather than pulled from a DOM binding library — Kotlin/Wasm JS
// interop has no `dynamic`, and this repo declares the members it actually uses
// rather than a whole lib.dom surface nothing here reads.

internal external interface JsAudioTimeRanges : JsAny {
    val length: Int
}

@JsFun("(ranges, i) => ranges.start(i)")
internal external fun jsAudioRangeStart(ranges: JsAudioTimeRanges, index: Int): Double

@JsFun("(ranges, i) => ranges.end(i)")
internal external fun jsAudioRangeEnd(ranges: JsAudioTimeRanges, index: Int): Double

internal external interface JsAudioMediaError : JsAny {
    val code: Int
    val message: JsString?
}

// The subset of HTMLAudioElement this engine touches.
internal external interface JsAudioElement : JsAny {
    var src: JsString
    var currentTime: Double
    val duration: Double
    var volume: Double
    var muted: Boolean
    var playbackRate: Double
    var preload: JsString
    var crossOrigin: JsString?
    val paused: Boolean
    val ended: Boolean
    val readyState: Int
    val buffered: JsAudioTimeRanges
    val seekable: JsAudioTimeRanges
    val error: JsAudioMediaError?

    fun play(): JsAny?
    fun pause()
    fun load()
    fun addEventListener(type: JsString, listener: (JsAny) -> Unit)
    fun removeEventListener(type: JsString, listener: (JsAny) -> Unit)
}

@JsFun("() => new Audio()")
internal external fun jsCreateAudioElement(): JsAudioElement

// `removeAttribute('src')` and not `src = ''`: an empty string is a relative URL
// the browser resolves against the page and then tries to fetch, which surfaces
// as a spurious media error on every stop.
@JsFun("(el) => { el.pause(); el.removeAttribute('src'); el.load(); }")
internal external fun jsResetAudioElement(el: JsAudioElement)
