// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.MediaBackend

data class TestItem(
    override val id: String,
    override val url: String = "https://example.test/$id",
    override val title: String? = null,
) : PlaylistItem

// Records what the controller asked the engine to do, so a test asserts the
// outcome of a real controller rather than that a mock of the controller was
// called. Only the engine below is fake.
class FakeMediaBackend : MediaBackend {
    var playCount: Int = 0
    var pauseCount: Int = 0
    var stopCount: Int = 0
    var muteCount: Int = 0
    var unmuteCount: Int = 0
    val loadedUrls: MutableList<String> = mutableListOf()
    val loadedOptions: MutableList<LoadOptions> = mutableListOf()
    val seekedTo: MutableList<Double> = mutableListOf()
    val volumesSet: MutableList<Float> = mutableListOf()
    val ratesSet: MutableList<Double> = mutableListOf()

    var bufferedValue: Double = 0.0
    var durationValue: Double = 0.0
    var currentTimeValue: Double = 0.0
    var stateValue: BackendState = BackendState.IDLE

    private var level: Float = 1.0f
    private var rate: Double = 1.0
    private val listeners = mutableMapOf<String, MutableList<(Any?) -> Unit>>()

    fun fire(event: String, data: Any? = null) {
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
class ThrowsOnStopBackend : MediaBackend by FakeMediaBackend() {
    var stopWasAttempted: Boolean = false
        private set

    override fun stop() {
        stopWasAttempted = true
        error("the engine was already gone")
    }
}

fun newContext(): PlayerContext = PlayerContext(backend = FakeMediaBackend())

fun PlayerContext.fakeBackend(): FakeMediaBackend = backend as FakeMediaBackend

// Records events in emission order with full payload typing, so a test asserts
// the exact sequence rather than that something fired.
class EventLog {
    val names: MutableList<String> = mutableListOf()

    fun <T> record(ctx: PlayerContext, key: EventKey<T>, label: (T) -> String) {
        ctx.on(key) { names += label(it) }
    }

    fun <T> capture(ctx: PlayerContext, key: EventKey<T>): MutableList<T> {
        val received: MutableList<T> = mutableListOf()
        ctx.on(key) { received += it }
        return received
    }
}
