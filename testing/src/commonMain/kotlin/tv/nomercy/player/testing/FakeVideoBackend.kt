// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.SubtitleTrack
import tv.nomercy.player.core.ports.VideoBackend

public open class FakeVideoBackend : VideoBackend {
    public var subtitleTracks: List<SubtitleTrack> = emptyList()
    public var audio: List<AudioTrack> = emptyList()
    public var levels: List<QualityLevel> = emptyList()

    public val seekedTo: MutableList<Double> = mutableListOf()

    // Counted, because "did this engine advance at all" is the question the cast
    // acceptance asks. A command reaching a television proves the phone spoke;
    // only this proves it did not also play the film into the room.
    public var playCount: Int = 0
        private set

    public var pauseCount: Int = 0
        private set

    private var position: Double = 0.0
    // Writable, so a test can model the ENGINE settling on a file's own default.
    // That selection does not go through the player's setter and announces no
    // event, which is the distinction a preferences plugin depends on: what it
    // saves is a viewer's choice, and the engine's default is not one.
    public var chosenSubtitle: SubtitleTrack? = null
    public var chosenAudio: AudioTrack? = null
    private var chosenQuality: QualityLevel? = null
    private var level: Float = 1.0f
    private var rate: Double = 1.0
    private val listeners: MutableMap<String, MutableList<(Any?) -> Unit>> = mutableMapOf()

    public fun fire(event: String, data: Any? = null) {
        listeners[event]?.toList()?.forEach { it(data) }
    }

    // Moves the playhead the way a real engine does — by reporting it, not by
    // being asked. A test that set a field would be asserting against a path
    // nothing takes.
    public fun tick(seconds: Double) {
        position = seconds
        fire(CanonicalBackendEvent.TIME_UPDATE)
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        fire(CanonicalBackendEvent.LOAD_START)
        fire(CanonicalBackendEvent.LOADED_METADATA)
        fire(CanonicalBackendEvent.CAN_PLAY)
    }

    override suspend fun play() {
        playCount += 1
        fire(CanonicalBackendEvent.PLAY)
        fire(CanonicalBackendEvent.PLAYING)
    }

    override fun pause() {
        pauseCount += 1
        fire(CanonicalBackendEvent.PAUSE)
    }
    override fun stop(): Unit = Unit
    override fun currentTime(): Double = position

    override fun currentTime(seconds: Double) {
        seekedTo += seconds
        position = seconds
    }

    override fun duration(): Double = 3_600.0
    override fun volume(): Float = level
    override fun volume(value: Float) {
        level = value
    }
    override fun mute(): Unit = Unit
    override fun unmute(): Unit = Unit
    override fun buffered(): Double = position
    override fun playbackRate(): Double = rate
    override fun playbackRate(rate: Double) { this.rate = rate }
    override fun state(): BackendState = BackendState.READY

    override fun on(event: String, fn: (Any?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(fn)
    }

    override fun off(event: String, fn: (Any?) -> Unit) {
        listeners[event]?.remove(fn)
    }

    override fun audioTracks(): List<AudioTrack> = audio
    override fun audioTrack(): AudioTrack? = chosenAudio ?: audio.firstOrNull()
    override fun audioTrack(track: AudioTrack) { chosenAudio = track }

    override fun subtitleTracks(): List<SubtitleTrack> = subtitleTracks
    override fun subtitleTrack(): SubtitleTrack? = chosenSubtitle
    override fun subtitleTrack(track: SubtitleTrack?) { chosenSubtitle = track }

    override fun qualityLevels(): List<QualityLevel> = levels
    override fun quality(): QualityLevel? = chosenQuality
    override fun quality(level: QualityLevel?) { chosenQuality = level }

    // Counted, because the defect this exists to catch is a seam that creates an
    // engine and never frees it — which is silent by construction, since what an
    // engine holds is native and the garbage collector cannot see it.
    public var releaseCount: Int = 0
        private set

    override fun release() { releaseCount += 1 }

}
