// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRACK_DURATION_SECONDS = 100.0

// Small enough to read, complete enough to prove the contract is implementable
// without an engine. The real ones wrap ExoPlayer, AVPlayer and VLCJ.
private open class FakeAudioBackend : AudioBackend {
    private var backendState: BackendState = BackendState.IDLE
    private var position: Double = 0.0
    private var level: Float = 1.0f
    private var levelBeforeMute: Float = 1.0f
    private var secondary: Float = 0.0f
    private var rate: Double = 1.0
    private val listeners = mutableMapOf<String, MutableList<(Any?) -> Unit>>()

    private fun fire(event: String, data: Any?) {
        listeners[event]?.toList()?.forEach { it(data) }
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        backendState = BackendState.LOADING
        fire(BackendEvents.LOAD_START, url)
        position = opts.startPositionMs / 1_000.0
        backendState = BackendState.READY
        fire(BackendEvents.CAN_PLAY, url)
        if (opts.autoplay) play()
    }

    override suspend fun play() {
        backendState = BackendState.PLAYING
        fire(BackendEvents.PLAYING, null)
    }

    override fun pause() {
        backendState = BackendState.PAUSED
        fire(BackendEvents.PAUSE, null)
    }

    override fun stop() {
        backendState = BackendState.IDLE
        position = 0.0
    }

    override fun release() {
        stop()
        listeners.clear()
    }

    override fun currentTime(): Double = position

    override fun currentTime(seconds: Double) {
        position = seconds
        fire(BackendEvents.TIME_UPDATE, seconds)
    }

    override fun duration(): Double = TRACK_DURATION_SECONDS

    override fun volume(): Float = level

    override fun volume(value: Float) {
        level = value.coerceIn(0.0f, 1.0f)
    }

    override fun mute() {
        levelBeforeMute = level
        level = 0.0f
    }

    override fun unmute() {
        level = levelBeforeMute
    }

    // buffered() is deliberately not overridden: the fake reports no ranges, and
    // taking the interface default is what makes the subclass below able to
    // prove the default answers for an engine that reports only ranges.

    override fun playbackRate(): Double = rate

    override fun playbackRate(rate: Double) {
        this.rate = rate
    }

    override fun state(): BackendState = backendState

    override fun on(event: String, fn: (Any?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(fn)
    }

    override fun off(event: String, fn: (Any?) -> Unit) {
        listeners[event]?.remove(fn)
    }

    override fun supportsCrossfade(): Boolean = true

    override suspend fun loadSecondary(url: String) = Unit

    override suspend fun primeSecondary(seekMs: Long) = Unit

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        secondary = curve.gain(1.0).toFloat()
    }

    override fun disposeSecondary() {
        secondary = 0.0f
    }

    override fun secondaryGain(): Float = secondary

    override fun secondaryGain(value: Float) {
        secondary = value.coerceIn(0.0f, 1.0f)
    }
}

// An engine that reports where its data is and nothing else, which is the Apple
// backend's shape. It must not have to know how a frontier is derived from that.
private class RangeReportingBackend(
    private val ranges: List<TimeRange>,
) : FakeAudioBackend() {
    override fun bufferedRanges(): List<TimeRange> = ranges
}

class MediaBackendContractTest {

    // The revert-proof for the Apple backend's own override, which took the
    // furthest end out of every range and so read the list below as 3600.
    //
    // Asserted through buffered() rather than through bufferedFrontier(), because
    // what broke was not the walk — it was an engine answering without it. A test
    // on the walk alone stays green while a backend routes around it.
    @Test
    fun anEngineThatReportsRangesGetsTheFrontierWithoutImplementingTheWalk() {
        val backend: MediaBackend = RangeReportingBackend(BufferedFrontierTest.SPLIT)

        backend.currentTime(5.0)

        assertEquals(90.0, backend.buffered())
    }

    @Test
    fun theFrontierIsAbsoluteRatherThanADurationAheadOfThePlayhead() {
        // 90, not 85. The doc said "how far ahead of the playhead" for a while
        // and every consumer divided by duration to draw a bar, which only works
        // if the number is a position on the same timeline.
        val backend: MediaBackend = RangeReportingBackend(BufferedFrontierTest.SPLIT)

        backend.currentTime(5.0)

        assertEquals(90.0, backend.buffered())
        assertTrue(backend.buffered() > backend.currentTime())
    }

    @Test
    fun anEngineInAHoleReportsNoBufferAheadRatherThanTheFurthestEnd() {
        val backend: MediaBackend = RangeReportingBackend(BufferedFrontierTest.SPLIT)

        backend.currentTime(2000.0)

        assertEquals(2000.0, backend.buffered())
    }

    @Test
    fun transportMovesTheBackendThroughItsStates() = runTest {
        val backend = FakeAudioBackend()
        assertEquals(BackendState.IDLE, backend.state())

        backend.load("https://example.test/a.m3u8")
        assertEquals(BackendState.READY, backend.state())

        backend.play()
        assertEquals(BackendState.PLAYING, backend.state())

        backend.pause()
        assertEquals(BackendState.PAUSED, backend.state())
    }

    @Test
    fun loadingEmitsTheWebMediaElementEventNamesInOrder() = runTest {
        val backend = FakeAudioBackend()
        val seen = mutableListOf<String>()
        listOf(BackendEvents.LOAD_START, BackendEvents.CAN_PLAY, BackendEvents.PLAYING)
            .forEach { name -> backend.on(name) { seen.add(name) } }

        backend.load("https://example.test/a.m3u8", LoadOptions(autoplay = true))

        // The conformance runners compare these sequences against the web trio,
        // so the names and their order are contract.
        assertEquals(listOf("loadstart", "canplay", "playing"), seen)
    }

    @Test
    fun aRemovedListenerStopsHearingEvents() = runTest {
        val backend = FakeAudioBackend()
        var calls = 0
        val listener: (Any?) -> Unit = { calls++ }
        backend.on(BackendEvents.TIME_UPDATE, listener)

        backend.currentTime(5.0)
        backend.off(BackendEvents.TIME_UPDATE, listener)
        backend.currentTime(10.0)

        assertEquals(1, calls)
        assertEquals(10.0, backend.currentTime())
    }

    @Test
    fun loadHonoursItsStartPositionAndAutoplay() = runTest {
        val backend = FakeAudioBackend()

        backend.load("https://example.test/a.m3u8", LoadOptions(startPositionMs = 30_000, autoplay = true))

        assertEquals(30.0, backend.currentTime())
        assertEquals(BackendState.PLAYING, backend.state())
    }

    @Test
    fun unmutingRestoresTheLevelThatWasSetBefore() = runTest {
        val backend = FakeAudioBackend()
        backend.volume(0.4f)

        backend.mute()
        assertEquals(0.0f, backend.volume())

        backend.unmute()
        assertEquals(0.4f, backend.volume())
    }

    @Test
    fun theBackendVolumeScaleIsZeroToOneAndClamps() = runTest {
        val backend = FakeAudioBackend()

        backend.volume(2.0f)

        // The player's own scale is 0..100; converting at the boundary is the
        // controller's job, so an engine never has to know about both.
        assertEquals(1.0f, backend.volume())
    }

    @Test
    fun crossfadeEndsWithTheSecondaryAtFullGain() = runTest {
        val backend = FakeAudioBackend()
        backend.secondaryGain(2.0f)
        assertEquals(1.0f, backend.secondaryGain())

        backend.crossfade(3_000, CrossfadeCurve.EQUAL_POWER)

        assertTrue(backend.secondaryGain() > 0.99f)
        backend.disposeSecondary()
        assertEquals(0.0f, backend.secondaryGain())
    }

    @Test
    fun anAudioBackendIsAMediaBackendAndATransitionBackend() {
        val backend: AudioBackend = FakeAudioBackend()

        val asMedia: MediaBackend = backend
        val asTransition: TransitionBackend = backend

        assertEquals(BackendState.IDLE, asMedia.state())
        assertTrue(asTransition.supportsCrossfade())
        // A video backend it is not, which is what keeps qualityLevels off it.
        assertNull(backend as? VideoBackend)
    }
}
