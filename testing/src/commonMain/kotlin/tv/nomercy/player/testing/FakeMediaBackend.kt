// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.TimeRange
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.MediaBackend

// An engine that reports rather than decodes.
//
// The one every test in this ecosystem is written against, shipped so a
// consumer testing their own integration writes none of their own. Three fakes
// for one interface is three answers to "what does this engine do when a seek
// lands past the end", and only the library's own answer is ever checked.
//
// It confirms actions on its event stream the way a real engine does. The
// difference between play and playing is the whole reason the bridge exists,
// and a stand-in that stayed silent would let that gap pass unnoticed.
public open class FakeMediaBackend : MediaBackend {
    public var playCount: Int = 0
    public var pauseCount: Int = 0
    public var stopCount: Int = 0
    public var muteCount: Int = 0
    public var unmuteCount: Int = 0
    public val loadedUrls: MutableList<String> = mutableListOf()
    public val loadedOptions: MutableList<LoadOptions> = mutableListOf()
    public val seekedTo: MutableList<Double> = mutableListOf()
    public val volumesSet: MutableList<Float> = mutableListOf()
    public val ratesSet: MutableList<Double> = mutableListOf()

    public var bufferedValue: Double = 0.0
    public var durationValue: Double = 0.0
    public var currentTimeValue: Double = 0.0
    public var stateValue: BackendState = BackendState.IDLE

    private var level: Float = 1.0f
    private var rate: Double = 1.0
    private val listeners = mutableMapOf<String, MutableList<(Any?) -> Unit>>()

    public fun fire(event: String, data: Any? = null) {
        listeners[event]?.toList()?.forEach { it(data) }
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        loadedUrls += url
        loadedOptions += opts
        // What a real engine reports once it has read the container.
        fire(CanonicalBackendEvent.LOADED_METADATA)
        fire(CanonicalBackendEvent.CAN_PLAY)
    }

    // A real engine confirms the action on its own event stream, and the
    // difference between play and playing is the whole reason the bridge
    // exists. A fake that stayed silent would let that gap pass unnoticed.
    override suspend fun play() {
        playCount += 1
        fire(CanonicalBackendEvent.PLAY)
        fire(CanonicalBackendEvent.PLAYING)
    }

    override fun pause() {
        pauseCount += 1
        fire(CanonicalBackendEvent.PAUSE)
    }

    override fun stop() { stopCount += 1 }

    // Counted, because the defect this exists to catch is a seam that creates an
    // engine and never frees it — which is silent by construction, since what an
    // engine holds is native and the garbage collector cannot see it.
    public var releaseCount: Int = 0
        private set

    override fun release() { releaseCount += 1 }

    override fun currentTime(): Double = currentTimeValue

    override fun currentTime(seconds: Double) {
        seekedTo += seconds
        currentTimeValue = seconds
    }

    override fun duration(): Double = durationValue

    override fun volume(): Float = level

    override fun volume(value: Float) {
        level = value
        volumesSet += value
    }

    override fun mute() { muteCount += 1 }
    override fun unmute() { unmuteCount += 1 }

    override fun buffered(): Double = bufferedValue

    override fun playbackRate(): Double = rate

    override fun playbackRate(rate: Double) {
        this.rate = rate
        ratesSet += rate
    }

    override fun state(): BackendState = stateValue

    override fun on(event: String, fn: (Any?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(fn)
    }

    override fun off(event: String, fn: (Any?) -> Unit) {
        listeners[event]?.remove(fn)
    }
}

// An engine that fails on the way out. Real ones do: libVLC can throw from
// release() when the native handle is already gone, and Media3 throws if stop()
// lands off the main thread.
