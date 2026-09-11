// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

/**
 * One `<audio>` element, driven as a [MediaBackend].
 *
 * The browser half of what `ExoPlayerVideoBackend` and `MpvVideoBackend` are on
 * the other platforms: a single engine that plays one thing. Crossfade needs two
 * of these, which is [Html5AudioBackend]'s job — the same split the Android
 * audio backend makes, for the same reason. One element cannot keep decoding an
 * outgoing track while an incoming one starts.
 *
 * The canonical event vocabulary IS the media element's own event names, so
 * unlike ExoPlayer's state integers there is no mapper here.
 */
@Suppress("TooManyFunctions")
public class Html5MediaBackend internal constructor(
    internal val element: JsAudioElement,
) : MediaBackend {

    public constructor() : this(jsCreateAudioElement())

    private val bus = StringEventBus()
    private val listeners: MutableList<Pair<String, (JsAny) -> Unit>> = mutableListOf()

    private var released: Boolean = false
    private var loadStarted: Boolean = false
    private var hadError: Boolean = false

    /** Where the pending load should begin, applied once the element has metadata. */
    private var pendingStartSeconds: Double? = null

    init {
        // The element is never in the document, so nothing tells the browser
        // this is worth buffering ahead of a play() — without it the first
        // track of a session starts on an empty buffer.
        element.preload = "auto".toJsString()
        // Media on a NoMercy server is a different origin from the receiver
        // page and is fetched with a token, so the request has to be a CORS one.
        element.crossOrigin = "anonymous".toJsString()
        wireElementEvents()
    }

    // ---- loading -------------------------------------------------------

    override suspend fun load(url: String, opts: LoadOptions) {
        hadError = false
        loadStarted = true
        bus.emit(CanonicalBackendEvent.LOAD_START)

        jsResetAudioElement(element)
        element.src = url.toJsString()
        element.load()

        // Held until the element has metadata. A currentTime written while the
        // element is still empty is discarded by every engine, so a resumed
        // track began at zero.
        pendingStartSeconds = (opts.startPositionMs / MILLIS_PER_SECOND).takeIf { opts.startPositionMs > 0L }

        if (opts.autoplay) play()
    }

    override suspend fun play() {
        element.play()
    }

    override fun pause() {
        element.pause()
    }

    override fun stop() {
        element.pause()
        element.currentTime = 0.0
    }

    override fun release() {
        if (released) return
        released = true
        jsResetAudioElement(element)
        for ((type, listener) in listeners) element.removeEventListener(type.toJsString(), listener)
        listeners.clear()
        bus.clear()
    }

    // ---- the playhead ---------------------------------------------------

    override fun currentTime(): Double = element.currentTime

    override fun currentTime(seconds: Double) {
        element.currentTime = seconds
    }

    // A stream still being written reports Infinity, and a progress bar divided
    // by that draws nothing. Zero is the contract's own "not known yet".
    override fun duration(): Double = element.duration.takeIf { it.isFinite() } ?: 0.0

    override fun volume(): Float = element.volume.toFloat()

    override fun volume(value: Float) {
        element.volume = value.toDouble().coerceIn(0.0, 1.0)
    }

    override fun mute() {
        element.muted = true
    }

    override fun unmute() {
        element.muted = false
    }

    override fun playbackRate(): Double = element.playbackRate

    override fun playbackRate(rate: Double) {
        element.playbackRate = rate
    }

    override fun bufferedRanges(): List<TimeRange> = element.buffered.toRangeList()

    override fun seekableRanges(): List<TimeRange> = element.seekable.toRangeList()

    private fun JsAudioTimeRanges.toRangeList(): List<TimeRange> =
        (0 until length).map { index -> TimeRange(jsAudioRangeStart(this, index), jsAudioRangeEnd(this, index)) }

    /**
     * A media element measures nothing it will tell anyone about. Zero is
     * [MediaBackend]'s own honest answer for "this engine does not measure",
     * not a stand-in for a number that exists somewhere.
     */
    override fun bandwidthEstimate(): Int = 0

    override fun state(): BackendState = when {
        released -> BackendState.IDLE
        hadError -> BackendState.ERROR
        !loadStarted -> BackendState.IDLE
        !element.paused && !element.ended -> BackendState.PLAYING
        element.paused && element.readyState >= READY_STATE_HAVE_CURRENT_DATA && !element.ended -> BackendState.PAUSED
        element.readyState >= READY_STATE_HAVE_METADATA -> BackendState.READY
        else -> BackendState.LOADING
    }

    // ---- events -------------------------------------------------------

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    private fun track(type: String, emit: String = type, payload: (JsAny) -> Any? = { null }) {
        val listener: (JsAny) -> Unit = { event -> bus.emit(emit, payload(event)) }
        element.addEventListener(type.toJsString(), listener)
        listeners.add(type to listener)
    }

    private fun wireElementEvents() {
        track(CanonicalBackendEvent.LOAD_START)
        track(CanonicalBackendEvent.LOADED_METADATA) {
            pendingStartSeconds?.let { seconds ->
                element.currentTime = seconds
                pendingStartSeconds = null
            }
            null
        }
        track(CanonicalBackendEvent.CAN_PLAY)
        track(CanonicalBackendEvent.PLAY)
        track(CanonicalBackendEvent.PLAYING)
        track(CanonicalBackendEvent.TIME_UPDATE) { currentTime() }
        track(CanonicalBackendEvent.DURATION_CHANGE)
        track(CanonicalBackendEvent.WAITING)
        track(CanonicalBackendEvent.STALLED)
        track(CanonicalBackendEvent.PAUSE)
        track("error", emit = CanonicalBackendEvent.ERROR) {
            hadError = true
            element.error?.message?.toString()
        }
        track(CanonicalBackendEvent.ENDED)
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1000.0
        const val READY_STATE_HAVE_METADATA: Int = 1
        const val READY_STATE_HAVE_CURRENT_DATA: Int = 2
    }
}
