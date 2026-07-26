// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MILLIS_PER_SECOND = 1000.0
private const val TIME_UPDATE_INTERVAL_MS = 250L

// The Android engine, over Media3.
//
// Media3 is what every Android client already uses, and reimplementing its
// buffering, its track selection or its DRM would be worse than anything gained
// by owning them. This class is the translation: Media3's state machine into
// the canonical event vocabulary every controller above is written against.
//
// Media3 is main-thread-only. Every call into it hops there, and every callback
// arrives there, which is why the event bus is locked — a controller may
// subscribe from anywhere.
public class ExoPlayerVideoBackend(
    context: Context,
    scope: CoroutineScope? = null,
) : VideoBackend {

    private val bus = StringEventBus()

    // Bound to the actual main looper rather than to Dispatchers.Main.
    //
    // Dispatchers.Main is whatever the coroutines runtime currently believes it
    // is, and kotlinx-coroutines-test replaces it the moment that library is on
    // the classpath — which it is in any instrumentation run. A library that
    // must post to Android's main looper should say so, not ask a dispatcher
    // registry that a test framework can swap out from under it.
    private val mainDispatcher: CoroutineDispatcher =
        Handler(Looper.getMainLooper()).asCoroutineDispatcher()

    private val main: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + mainDispatcher)
    // Public because video has to be drawn somewhere and only the caller knows
    // where. A PlayerView is handed the engine, not a frame buffer, so a backend
    // that kept this private could decode a film and show no one. Nothing above
    // the UI layer touches it: every playback call goes through MediaBackend,
    // and this is the render target alone.
    //
    // Main thread, like everything else Media3 owns.
    public val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val player: ExoPlayer = exoPlayer

    // Media3 has no periodic time callback: it expects the UI to poll on its own
    // frame loop. A backend has no frame loop, so it polls at the rate a
    // scrubber can actually show — four times a second, not sixty.
    private var ticker: Job? = null

    private var announcedCanPlay: Boolean = false

    // Media3 verifies the calling thread on every accessor, including the
    // getters, and this contract is synchronous and callable from anywhere. So
    // the readable state is mirrored here, written only on the main thread and
    // read from any of them.
    //
    // Blocking the caller to hop to main instead would deadlock the common case:
    // a chrome asking for the position from the main thread it is drawing on.
    // These values are snapshots either way — by the time a caller has one, the
    // playhead has moved.
    @Volatile private var cachedTime: Double = 0.0
    @Volatile private var cachedDuration: Double = 0.0
    @Volatile private var cachedBuffered: Double = 0.0
    @Volatile private var cachedVolume: Float = 1.0f
    @Volatile private var cachedRate: Double = 1.0
    @Volatile private var cachedState: BackendState = BackendState.IDLE

    // Tracks are read on the main thread like everything else Media3 owns, and
    // cached because a chrome asks for them from wherever it happens to be.
    @Volatile private var cachedQualityLevels: List<QualityLevel> = emptyList()
    @Volatile private var cachedQuality: QualityLevel? = null
    @Volatile private var cachedAudioTracks: List<AudioTrack> = emptyList()
    @Volatile private var cachedSubtitleTracks: List<SubtitleTrack> = emptyList()
    @Volatile private var cachedSubtitleTrack: SubtitleTrack? = null
    @Volatile private var cachedAudioTrack: AudioTrack? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                refreshCache()
                when (state) {
                    Player.STATE_BUFFERING -> bus.emit(CanonicalBackendEvent.WAITING)
                    Player.STATE_READY -> {
                        if (!announcedCanPlay) {
                            announcedCanPlay = true
                            bus.emit(CanonicalBackendEvent.LOADED_METADATA)
                            bus.emit(CanonicalBackendEvent.CAN_PLAY)
                        }
                    }
                    Player.STATE_ENDED -> bus.emit(CanonicalBackendEvent.ENDED)
                    else -> Unit
                }
            }

            // Media3 reports "is playing" rather than a play event, and it is
            // false while buffering even though playback was requested. That is
            // exactly the play/playing distinction, so it maps to playing.
            // Without this the track cache never moves. Every other callback
            // here reports playback, and a selection change reports nothing —
            // so a chrome reading back what it just chose got the previous
            // answer, and two selections in a row read as inverted.
            override fun onTracksChanged(tracks: Tracks) {
                refreshCache()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshCache()
                if (isPlaying) {
                    bus.emit(CanonicalBackendEvent.PLAYING)
                    startTicking()
                } else {
                    bus.emit(CanonicalBackendEvent.PAUSE)
                    stopTicking()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                bus.emit(CanonicalBackendEvent.ERROR, error.errorCodeName)
            }
        })
    }

    override suspend fun load(url: String, opts: LoadOptions): Unit = onMain {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        announcedCanPlay = false
        player.setMediaItem(MediaItem.fromUri(url))
        // prepare, not play: starting is a separate decision above, and an
        // engine that started on its own would ignore a refused beforePlay.
        player.prepare()
        if (opts.startPositionMs > 0L) player.seekTo(opts.startPositionMs)
    }

    override suspend fun play(): Unit = onMain {
        bus.emit(CanonicalBackendEvent.PLAY)
        player.play()
    }

    override fun pause(): Unit = fireAndForget { player.pause() }

    override fun stop(): Unit = fireAndForget {
        stopTicking()
        player.stop()
    }

    override fun currentTime(): Double = cachedTime

    override fun currentTime(seconds: Double): Unit = fireAndForget {
        player.seekTo((seconds * MILLIS_PER_SECOND).toLong())
    }

    override fun duration(): Double = cachedDuration

    override fun volume(): Float = cachedVolume

    override fun volume(value: Float): Unit = fireAndForget { player.volume = value }

    override fun mute(): Unit = fireAndForget { player.volume = 0f }

    override fun unmute(): Unit = fireAndForget { player.volume = 1f }

    override fun buffered(): Double = cachedBuffered

    override fun playbackRate(): Double = cachedRate

    override fun playbackRate(rate: Double): Unit = fireAndForget { player.setPlaybackSpeed(rate.toFloat()) }

    override fun state(): BackendState = cachedState

    // Main-thread only. Every path that changes what the engine would report
    // calls this, so a caller on any thread reads a value that was true a
    // moment ago rather than one Media3 refuses to give it.
    //
    // Media3's ENDED has no counterpart in this vocabulary and READY covers it:
    // playback stopped at a known position with the media still loaded.
    private fun refreshCache() {
        cachedTime = player.currentPosition.coerceAtLeast(0) / MILLIS_PER_SECOND
        val reportedDuration: Long = player.duration
        cachedDuration = if (reportedDuration > 0) reportedDuration / MILLIS_PER_SECOND else 0.0
        cachedBuffered = player.bufferedPosition.coerceAtLeast(0) / MILLIS_PER_SECOND
        cachedVolume = player.volume
        cachedRate = player.playbackParameters.speed.toDouble()
        val tracks: Tracks = player.currentTracks
        cachedQualityLevels = ExoTrackMapper.qualityLevels(tracks)
        cachedQuality = ExoTrackMapper.selectedQuality(tracks)
        cachedAudioTracks = ExoTrackMapper.audioTracks(tracks)
        cachedSubtitleTracks = ExoTrackMapper.subtitleTracks(tracks)
        cachedAudioTrack = ExoTrackMapper.selectedAudioTrack(tracks)
        cachedSubtitleTrack = ExoTrackMapper.selectedSubtitleTrack(tracks)
        cachedState = when (player.playbackState) {
            Player.STATE_READY -> if (player.isPlaying) BackendState.PLAYING else BackendState.PAUSED
            Player.STATE_BUFFERING -> BackendState.LOADING
            Player.STATE_ENDED -> BackendState.READY
            else -> BackendState.IDLE
        }
    }

    override fun qualityLevels(): List<QualityLevel> = cachedQualityLevels

    override fun quality(): QualityLevel? = cachedQuality

    // Null hands the choice back to Media3's own adaptation, which is what a
    // viewer means by "auto". A descriptor pins one rung, and QualityMatcher is
    // the only thing that turns it into a number this engine understands.
    override fun quality(level: QualityLevel?): Unit = fireAndForget {
        val override: TrackSelectionOverride? = level?.let(::overrideFor)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .apply { override?.let(::addOverride) }
            .build()
        refreshCache()
    }

    // The descriptor is matched against the engine's own list, in the engine's
    // own order, which is the one place the two numbering schemes meet. A rung
    // the engine no longer has clears the override rather than picking a
    // neighbour: silently playing something else is worse than playing auto.
    private fun overrideFor(level: QualityLevel): TrackSelectionOverride? {
        val groups: List<Tracks.Group> = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
        val flattened: List<Pair<Tracks.Group, Int>> = groups
            .flatMap { group -> (0 until group.length).map { group to it } }
        val levels: List<QualityLevel> = ExoTrackMapper.qualityLevels(player.currentTracks)

        val index: Int = QualityMatcher.match(level, levels) ?: return null
        val (group, track) = flattened.getOrNull(index) ?: return null
        return TrackSelectionOverride(group.mediaTrackGroup, track)
    }

    override fun audioTracks(): List<AudioTrack> = cachedAudioTracks

    override fun audioTrack(): AudioTrack? = cachedAudioTrack

    override fun audioTrack(track: AudioTrack): Unit = selectByType(C.TRACK_TYPE_AUDIO, track.id)

    override fun subtitleTracks(): List<SubtitleTrack> = cachedSubtitleTracks

    override fun subtitleTrack(): SubtitleTrack? = cachedSubtitleTrack

    // Null is captions off, which is a selection a viewer makes rather than an
    // error. Media3 needs both an empty override and the type disabled, because
    // clearing the override alone lets its default selection pick one back up.
    override fun subtitleTrack(track: SubtitleTrack?): Unit = fireAndForget {
        if (track == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            selectByTypeOnMain(C.TRACK_TYPE_TEXT, track.id)
        }
        refreshCache()
    }

    private fun selectByType(type: Int, id: String): Unit = fireAndForget {
        selectByTypeOnMain(type, id)
        refreshCache()
    }

    // By id, because that is what the track the caller was handed carries. An
    // index would be this engine's numbering, which is exactly what the rest of
    // the library refuses to pass around.
    private fun selectByTypeOnMain(type: Int, id: String) {
        val located: Pair<Tracks.Group, Int> = player.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map { group to it } }
            .firstOrNull { (group, track) -> group.getTrackFormat(track).id == id }
            ?: return

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .addOverride(TrackSelectionOverride(located.first.mediaTrackGroup, located.second))
            .build()
    }

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    // Not optional: an ExoPlayer that is dropped without this keeps its codec
    // and its audio focus, and the next one starts against a device that still
    // thinks something is playing.
    public fun release(): Unit = fireAndForget {
        stopTicking()
        player.release()
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = main.launch {
            while (isActive) {
                delay(TIME_UPDATE_INTERVAL_MS)
                refreshCache()
                bus.emit(CanonicalBackendEvent.TIME_UPDATE, cachedTime)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private suspend fun onMain(block: () -> Unit): Unit = withContext(mainDispatcher) {
        block()
        refreshCache()
    }

    // The synchronous half of the contract against a main-thread-only engine.
    // Launching rather than blocking, because blocking the caller to satisfy
    // Media3's threading rule would deadlock a caller already on main.
    private fun fireAndForget(block: () -> Unit) {
        main.launch {
            block()
            refreshCache()
        }
    }
}
