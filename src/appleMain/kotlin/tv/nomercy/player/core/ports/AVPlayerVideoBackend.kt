// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicContainsOnlyForcedSubtitles
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.accessLog
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.preferredPeakBitRate
import platform.AVFoundation.presentationSize
import platform.AVFoundation.selectMediaOption
import kotlin.concurrent.Volatile
import platform.AVFoundation.AVAsset
import platform.AVFoundation.hasMediaCharacteristic
import platform.CoreGraphics.CGSize
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.playable
import platform.AVFoundation.statusOfValueForKey
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
import platform.darwin.dispatch_queue_create

private const val TIME_OBSERVER_HZ = 4.0
private const val NANOS_PER_SECOND = 1_000_000_000
private const val MILLIS_PER_SECOND = 1000.0
private const val PLAYABLE_KEY = "playable"
// duration and tracks as well: without them the item reports an indefinite
// duration and a scrubber has nothing to draw.
private val LOADED_KEYS = listOf(PLAYABLE_KEY, "duration", "tracks")

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
public class AVPlayerVideoBackend : VideoBackend {

    private val bus = StringEventBus()
    // Public because video has to be drawn somewhere and only the caller knows
    // where. AVPlayerLayer is handed the player itself, so a backend that kept
    // this private could decode a film and show no one. Nothing above the UI
    // layer touches it: every playback call goes through MediaBackend, and this
    // is the render target alone.
    public val avPlayer: AVPlayer = AVPlayer()

    private val player: AVPlayer = avPlayer

    // AVPlayer reports position through a periodic observer rather than a
    // property change, at whatever rate is asked for. Four times a second is
    // what a scrubber can show; asking for sixty would wake the CPU for nothing.
    private var timeObserver: Any? = null

    // The observer runs on its own serial queue rather than the main one.
    //
    // Passing null means the main queue, and then nothing is reported while the
    // main thread is busy — no canplay, no timeupdate, until whatever is running
    // there yields. A media backend that goes silent because the UI thread is
    // working is a backend that cannot tell you it is buffering during the one
    // moment that matters. The gate found this: it recorded loadstart, play and
    // pause and nothing in between.
    private val observerQueue = dispatch_queue_create("tv.nomercy.player.avplayer.observer", null)

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
            queue = observerQueue,
        ) { _ ->
            refreshCache()
            reportStatusChanges()
            bus.emit(CanonicalBackendEvent.TIME_UPDATE, cachedTime)
        }
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        announcedCanPlay = false

        // The asset is loaded before the item is built, rather than after.
        //
        // An item made from an unloaded asset is not ready to play, and play() on
        // a not-ready item silently does nothing — the gate saw canplay followed
        // by a playhead that never moved. An item made from an already-loaded
        // asset is ready as soon as the player has it.
        //
        // Two earlier attempts read readiness from the wrong place: the periodic
        // time observer, which only fires once time is moving and so can never
        // report that time may start; and the asset being playable, which is
        // true before its item is ready.
        val asset: AVURLAsset? = NSURL.URLWithString(url)?.let { AVURLAsset(uRL = it, options = null) }
        if (asset == null) {
            bus.emit(CanonicalBackendEvent.ERROR)
            return
        }

        asset.loadValuesAsynchronouslyForKeys(LOADED_KEYS) { onAssetLoaded(asset) }
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

    // AVFoundation does not publish an HLS variant list. The public API gives
    // the rendition currently being played and a ceiling to cap adaptation with,
    // which is genuinely all it knows — so the ladder here is one rung, not an
    // invented list, and pinning is a bitrate ceiling rather than a variant.
    override fun qualityLevels(): List<QualityLevel> = listOfNotNull(currentRendition())

    override fun quality(): QualityLevel? = currentRendition()

    // A ceiling, not a selection, and the difference is worth stating: AVPlayer
    // will still adapt below the number. Null lifts the ceiling, which is what
    // automatic means everywhere else in this library.
    override fun quality(level: QualityLevel?) {
        val item: AVPlayerItem = player.currentItem ?: return
        item.preferredPeakBitRate = level?.bitrate?.toDouble() ?: 0.0
    }

    private fun currentRendition(): QualityLevel? {
        val item: AVPlayerItem = player.currentItem ?: return null
        val size: CValue<CGSize> = item.presentationSize
        val height: Int = size.useContents { height }.toInt()
        if (height <= 0) return null

        return QualityLevel(
            height = height,
            bitrate = AVTrackMapper.bitrateOf(item.accessLog()?.events?.lastOrNull()
                ?.let { (it as? AVPlayerItemAccessLogEvent)?.indicatedBitrate?.toFloat() } ?: 0f),
            codec = "unknown",
            dynamicRange = DynamicRange.SDR,
            width = size.useContents { width }.toInt().takeIf { it > 0 },
        )
    }

    override fun audioTracks(): List<AudioTrack> =
        optionsIn(AUDIBLE).mapIndexed { index, option ->
            AudioTrack(
                id = "audio:$index",
                language = AVTrackMapper.languageOf(option.extendedLanguageTag),
                label = AVTrackMapper.labelOf(option.displayName, option.extendedLanguageTag),
            )
        }

    override fun audioTrack(): AudioTrack? =
        selectedIndexIn(AUDIBLE)?.let { audioTracks().getOrNull(it) }

    override fun audioTrack(track: AudioTrack): Unit = select(AUDIBLE, track.id)

    override fun subtitleTracks(): List<SubtitleTrack> =
        optionsIn(LEGIBLE).mapIndexed { index, option ->
            SubtitleTrack(
                id = "text:$index",
                language = AVTrackMapper.languageOf(option.extendedLanguageTag),
                label = AVTrackMapper.labelOf(option.displayName, option.extendedLanguageTag),
                // Asked of the option rather than read off a list, because
                // AVFoundation exposes the characteristics as a predicate and
                // the list form is not in the generated interop.
                forced = FORCED_SUBTITLES?.let(option::hasMediaCharacteristic) ?: false,
            )
        }

    override fun subtitleTrack(): SubtitleTrack? =
        selectedIndexIn(LEGIBLE)?.let { subtitleTracks().getOrNull(it) }

    // Null selects nothing in the group, which is AVFoundation's way of saying
    // captions off — a selection rather than an error.
    override fun subtitleTrack(track: SubtitleTrack?) {
        val item: AVPlayerItem = player.currentItem ?: return
        val group: AVMediaSelectionGroup = groupFor(LEGIBLE) ?: return
        val option = track?.id?.substringAfter(':')?.toIntOrNull()
            ?.let { group.options.getOrNull(it) as? AVMediaSelectionOption }
        item.selectMediaOption(option, group)
    }

    private fun groupFor(characteristic: String): AVMediaSelectionGroup? =
        currentAsset()?.mediaSelectionGroupForMediaCharacteristic(characteristic)

    // Kept from the load rather than read back off the item. The interop does
    // not surface AVPlayerItem's asset, and the backend already had it —
    // holding the one it loaded is both simpler and unambiguous about which
    // asset the selection groups belong to.
    private fun currentAsset(): AVAsset? = loadedAsset

    private fun optionsIn(characteristic: String): List<AVMediaSelectionOption> =
        groupFor(characteristic)?.options.orEmpty().mapNotNull { it as? AVMediaSelectionOption }

    private fun selectedIndexIn(characteristic: String): Int? {
        val item: AVPlayerItem = player.currentItem ?: return null
        val group: AVMediaSelectionGroup = groupFor(characteristic) ?: return null
        val selected = item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(group)
            ?: return null
        return optionsIn(characteristic).indexOfFirst { it == selected }.takeIf { it >= 0 }
    }

    private fun select(characteristic: String, id: String) {
        val item: AVPlayerItem = player.currentItem ?: return
        val group: AVMediaSelectionGroup = groupFor(characteristic) ?: return
        val index: Int = id.substringAfter(':').toIntOrNull() ?: return
        val option = group.options.getOrNull(index) as? AVMediaSelectionOption ?: return
        item.selectMediaOption(option, group)
    }

    @Volatile private var loadedAsset: AVAsset? = null

    private fun onAssetLoaded(asset: AVURLAsset) {
        val loaded: Boolean = asset.statusOfValueForKey(PLAYABLE_KEY, null) == AVKeyValueStatusLoaded
        if (!loaded || !asset.playable) {
            bus.emit(CanonicalBackendEvent.ERROR)
            return
        }
        loadedAsset = asset
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
        refreshCache()
        announceReadyOnce()
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
        if (cachedState == BackendState.IDLE) cachedState = BackendState.READY
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

// Non-null copies of AVFoundation's characteristic constants.
//
// Kotlin/Native types every ObjC string constant as nullable, and threading a
// null check through six call sites for values the framework always defines
// reads as uncertainty that does not exist.
private val AUDIBLE: String = AVMediaCharacteristicAudible ?: "public.audible"
private val LEGIBLE: String = AVMediaCharacteristicLegible ?: "public.legible"
private val FORCED_SUBTITLES: String? = AVMediaCharacteristicContainsOnlyForcedSubtitles
