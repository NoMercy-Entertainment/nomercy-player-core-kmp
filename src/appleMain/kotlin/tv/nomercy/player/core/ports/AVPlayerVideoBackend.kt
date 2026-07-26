// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

private const val TIME_OBSERVER_HZ = 4.0
private const val NANOS_PER_SECOND = 1_000_000_000
private const val MILLIS_PER_SECOND = 1000.0

// The Apple engine, over AVFoundation.
//
// AVPlayer is the only decoder on iOS and tvOS that gets hardware decode,
// background audio and AirPlay without fighting the platform, so the job here is
// not choosing an engine but translating one: AVPlayer's status properties into
// the canonical event vocabulary every controller above is written against.
//
// AVFoundation reports through KVO and notifications rather than a listener
// interface, and its numbers are CMTime rather than seconds. Both conversions
// happen here, once.
@OptIn(ExperimentalForeignApi::class)
public class AVPlayerVideoBackend : MediaBackend {

    private val bus = StringEventBus()
    private val player: AVPlayer = AVPlayer()

    // AVPlayer reports position through a periodic observer rather than a
    // property change, at whatever rate is asked for. Four times a second is
    // what a scrubber can show; asking for sixty would wake the CPU for nothing.
    private var timeObserver: Any? = null

    private var announcedCanPlay: Boolean = false

    // Mirrors of what the engine last reported. AVFoundation is main-thread
    // affine like every other Apple UI object, and the contract above is
    // synchronous and callable from anywhere.
    private var cachedTime: Double = 0.0
    private var cachedDuration: Double = 0.0
    private var cachedVolume: Float = 1.0f
    private var cachedRate: Double = 1.0
    private var cachedState: BackendState = BackendState.IDLE

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            cachedState = BackendState.READY
            bus.emit(CanonicalBackendEvent.ENDED)
        }

        timeObserver = player.addPeriodicTimeObserverForInterval(
            interval = CMTimeMakeWithSeconds(1.0 / TIME_OBSERVER_HZ, NANOS_PER_SECOND),
            queue = null,
        ) { _ ->
            refreshCache()
            reportStatusChanges()
            bus.emit(CanonicalBackendEvent.TIME_UPDATE, cachedTime)
        }
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        announcedCanPlay = false
        val item: AVPlayerItem? = NSURL.URLWithString(url)?.let { AVPlayerItem(uRL = it) }
        player.replaceCurrentItemWithPlayerItem(item)
        if (opts.startPositionMs > 0L) {
            player.seekToTime(
                CMTimeMakeWithSeconds(opts.startPositionMs / MILLIS_PER_SECOND, NANOS_PER_SECOND),
            )
        }
        refreshCache()
    }

    override suspend fun play() {
        bus.emit(CanonicalBackendEvent.PLAY)
        player.play()
        refreshCache()
    }

    override fun pause() {
        player.pause()
        refreshCache()
        bus.emit(CanonicalBackendEvent.PAUSE)
    }

    override fun stop() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        refreshCache()
    }

    override fun currentTime(): Double = cachedTime

    override fun currentTime(seconds: Double) {
        player.seekToTime(CMTimeMakeWithSeconds(seconds, NANOS_PER_SECOND))
        refreshCache()
    }

    override fun duration(): Double = cachedDuration

    override fun volume(): Float = cachedVolume

    override fun volume(value: Float) {
        player.volume = value
        cachedVolume = value
    }

    override fun mute() {
        player.muted = true
    }

    override fun unmute() {
        player.muted = false
    }

    // AVPlayer exposes loaded ranges on the item rather than the player, and
    // before an item exists there is nothing loaded. The position is the honest
    // floor rather than a guess at how far ahead it has read.
    override fun buffered(): Double = cachedTime

    override fun playbackRate(): Double = cachedRate

    override fun playbackRate(rate: Double) {
        player.rate = rate.toFloat()
        cachedRate = rate
    }

    override fun state(): BackendState = cachedState

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    public fun release() {
        timeObserver?.let { player.removeTimeObserver(it) }
        timeObserver = null
        player.replaceCurrentItemWithPlayerItem(null)
    }

    // AVFoundation has no "can play" callback: the item's status becomes
    // ReadyToPlay and whoever is watching notices. The periodic observer is
    // already running, so it is what notices.
    private fun reportStatusChanges() {
        val item: AVPlayerItem = player.currentItem ?: return
        when (item.status) {
            AVPlayerItemStatusReadyToPlay -> announceReadyOnce()
            AVPlayerItemStatusFailed -> bus.emit(CanonicalBackendEvent.ERROR)
            else -> Unit
        }
        reportTimeControlChanges()
    }

    private fun announceReadyOnce() {
        if (announcedCanPlay) return
        announcedCanPlay = true
        bus.emit(CanonicalBackendEvent.LOADED_METADATA)
        bus.emit(CanonicalBackendEvent.CAN_PLAY)
    }

    private fun reportTimeControlChanges() {
        when (player.timeControlStatus) {
            AVPlayerTimeControlStatusPlaying -> if (cachedState != BackendState.PLAYING) {
                cachedState = BackendState.PLAYING
                bus.emit(CanonicalBackendEvent.PLAYING)
            }
            // Waiting is AVFoundation saying it wants to play and cannot yet,
            // which is the same thing every other engine calls waiting.
            AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> if (cachedState != BackendState.LOADING) {
                cachedState = BackendState.LOADING
                bus.emit(CanonicalBackendEvent.WAITING)
            }
            AVPlayerTimeControlStatusPaused -> cachedState = BackendState.PAUSED
            else -> Unit
        }
    }

    // CMTime is indefinite before the asset is read, and CMTimeGetSeconds gives
    // NaN for it. NaN reaching a scrubber draws nothing and explains nothing.
    private fun refreshCache() {
        cachedTime = CMTimeGetSeconds(player.currentTime()).takeIf { it.isFinite() && it > 0 } ?: 0.0
        cachedDuration = player.currentItem?.duration
            ?.let { CMTimeGetSeconds(it) }
            ?.takeIf { it.isFinite() && it > 0 }
            ?: 0.0
        cachedVolume = player.volume
        cachedRate = player.rate.toDouble()
    }
}
