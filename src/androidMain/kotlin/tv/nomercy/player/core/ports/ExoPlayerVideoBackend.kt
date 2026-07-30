// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nomercy.player.core.errors.CoreErrorCodes

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
//
// The method count is VideoBackend's, not a decision made here. Transport,
// tracks and a quality ladder is what an engine has to answer, and splitting
// the implementation to satisfy a threshold would put half of one object's
// state behind a delegate — which is how the track cache and the playback cache
// would drift apart.
@Suppress("TooManyFunctions")
public class ExoPlayerVideoBackend(
    context: Context,
    scope: CoroutineScope? = null,
    // Only the audio backend passes one. Video has no equaliser today, and a
    // processor in a video sink would cost a pass over every sample to change
    // nothing.
    private val equaliser: BiquadEqAudioProcessor? = null,
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
    // The credentials every manifest and segment request carries.
    //
    // A host sets `authHeaders.provider` once and refreshes behind it; the
    // engine asks per request rather than capturing a token that expires
    // partway through a film.
    public val authHeaders: AuthHeaders = AuthHeaders()

    // Held rather than reached for through the player, because the tunneling
    // decision is re-applied per item and the engine's own accessor gives back
    // parameters rather than the selector that owns them.
    private val trackSelector: DefaultTrackSelector = DefaultTrackSelector(context)

    // Read once. A device does not stop being a television while the app runs,
    // and this is consulted on every load.
    private val isTvDevice: Boolean = bufferConfigForDevice(context).isTvDevice

    // What the selector was actually told, rather than what this class decided.
    //
    // Read straight off Media3's own parameters so a gate cannot pass by
    // agreeing with a field the setter wrote. Not public: a consumer has no
    // decision to make here, and exposing it would invite one.
    internal val tunnelingActive: Boolean
        get() = trackSelector.parameters.tunnelingEnabled

    // Latches on for this player's lifetime once the audio sink refuses a
    // tunneled configuration. Retrying tunneling after it has been refused once
    // gives a viewer the same failure on every item, and the sink's answer does
    // not change while the same cable is plugged into the same receiver.
    private var tunnelingRefusedByAudioSink: Boolean = false

    // Whether a video output surface exists yet, and therefore whether
    // tunneling may be asked for at all.
    //
    // Tunneling hands decoded frames straight to the display pipeline, so it
    // requires a surface to hand them to. Enabled without one, the decoder
    // never initialises and the item never becomes ready — no error, no event,
    // just a player that sits there. That is what it did on the television: the
    // audio-menu gates went red with "never reported metadata" while the phone,
    // which never tunnels, was unaffected.
    //
    // Default off and opt-in because this class does not own the surface. The
    // engine is public precisely so a host can attach a PlayerView to it, and
    // only the host knows when it has.
    public var videoSurfaceAttached: Boolean = false

    private val engine: ExoEngine = buildEngine(context, authHeaders, trackSelector, equaliser)

    // Public because video has to be drawn somewhere and only the caller knows
    // where. A PlayerView is handed the engine, not a frame buffer, so a backend
    // that kept this private could decode a film and show no one. Nothing above
    // the UI layer touches it: every playback call goes through MediaBackend,
    // and this is the render target alone.
    //
    // Main thread, like everything else Media3 owns.
    public val exoPlayer: ExoPlayer = engine.player

    private val player: ExoPlayer = exoPlayer

    // Read once. A display's HDR support does not change while an app runs, and
    // asking the DisplayManager on every tracks change would be a binder call
    // per manifest refresh.
    private val displayIsHdr: Boolean = androidDisplayIsHdr(context)

    // Android 12 gave MediaCodec a colour-transfer request, and below it there is
    // no way to ask a decoder for a converted picture at all. Reported rather than
    // assumed so hdrDecision falls through to the consumer's fallback on an older
    // device instead of promising a conversion nothing performs.
    override val canToneMapHdrToSdr: Boolean =
        Build.VERSION.SDK_INT >= ToneMappingCodecAdapterFactory.MIN_SDK

    private var hdrFallback: HdrOnSdrFallback = HdrOnSdrFallback.Play

    override fun hdrOnSdrFallback(fallback: HdrOnSdrFallback) {
        hdrFallback = fallback
    }

    // Latched per item, so a refusal is reported once rather than on every tracks
    // change for the rest of the film.
    private var refusedAsUnplayable: Boolean = false

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
            // Every event, rather than a list of the ones someone remembered.
            //
            // This cache exists because Media3's getters are main-thread-only,
            // and it was refreshed from three playback callbacks — so a track
            // selection did not move it (two selections in a row read as
            // inverted) and neither did a volume change (a gain set and read
            // back came out as the previous value). Enumerating callbacks means
            // missing one, and the one missed is a stale answer nobody can
            // explain.
            //
            // onEvents fires after every batch of changes with the player
            // already settled, which is the one place that cannot be
            // incomplete.
            override fun onEvents(player: Player, events: Player.Events) {
                refreshCache()
                applyHdrDecision()
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
                if (recoverFromTunnelingRefusal(error)) return
                bus.emit(CanonicalBackendEvent.ERROR, error.errorCodeName)
            }
        })
    }

    // Re-decided per item, because it depends on the container rather than the
    // device. Tunneling is what lets HDR frames reach the display pipeline
    // untouched on a direct MP4, and it breaks HLS over TS — so a player that
    // decided once at construction would be wrong for half a library.
    private fun applyTunneling(url: String) {
        // Never alongside a tone-map request. Tunneling exists to hand HDR frames
        // to the display pipeline untouched, which is the opposite of asking the
        // decoder to convert them — and a tunneled decoder ignores the colour
        // transfer request, so the two together would report a conversion and show
        // the washed-out picture anyway.
        val wanted: Boolean = !engine.renderers.requestSdrToneMap &&
            videoSurfaceAttached && TunnelingRule.shouldTunnel(
            isTv = isTvDevice,
            sourceIsHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
            refusedByAudioSink = tunnelingRefusedByAudioSink,
        )
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTunnelingEnabled(wanted)
            .build()
    }

    // A hostile audio HAL refuses a tunneled configuration by failing to
    // initialise the AudioTrack, which surfaces as a fatal playback error even
    // though the film is fine. Latching the refusal off and reloading turns an
    // ended session into a hiccup.
    //
    // Returns whether it handled the error, so the caller does not also report
    // one the viewer is about to stop seeing.
    private fun recoverFromTunnelingRefusal(error: PlaybackException): Boolean {
        val tunneling: Boolean = trackSelector.parameters.tunnelingEnabled
        if (!TunnelingRule.isTunnelingRefusal(error.errorCode, tunneling)) return false

        tunnelingRefusedByAudioSink = true
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTunnelingEnabled(false)
            .build()
        player.prepare()
        return true
    }

    override suspend fun load(url: String, opts: LoadOptions): Unit = onMain {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        announcedCanPlay = false
        refusedAsUnplayable = false
        // Armed BEFORE prepare, because a codec reads its colour-transfer request
        // when it is configured and the ladder is not known until after. Arming it
        // on an SDR display costs nothing on SDR content: the factory only asks
        // when the format actually reaching the decoder carries an HDR transfer,
        // which is precisely the case CapTo has already ruled out.
        engine.renderers.requestSdrToneMap = !displayIsHdr && canToneMapHdrToSdr
        applyTunneling(url)
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

    // The cache is updated here, not only when Media3 confirms.
    //
    // Setting anything on this engine means posting to the main thread, so a
    // caller that sets a value and reads it back on the next line gets the
    // previous one. On a volume slider that is the thumb jumping back under the
    // finger; on a crossfade it is a gain that reads as never having been set.
    //
    // Optimistic rather than wrong: onEvents still refreshes from the player
    // afterwards, so if the engine clamps or refuses a value the cache converges
    // on what actually happened.
    override fun volume(value: Float): Unit = setVolume(value)

    override fun mute(): Unit = setVolume(0f)

    override fun unmute(): Unit = setVolume(1f)

    private fun setVolume(value: Float) {
        val clamped: Float = value.coerceIn(0f, 1f)
        pendingVolume = clamped
        cachedVolume = clamped
        fireAndForget {
            player.volume = clamped
            // Cleared only once the engine has it, so a refresh that lands in
            // between cannot report the old value back at the caller. Without
            // this the optimism above is undone by the next callback from an
            // unrelated change — a load finishing, a state moving — and the
            // gain reads as never having been set.
            if (pendingVolume == clamped) pendingVolume = null
        }
    }

    @Volatile private var pendingVolume: Float? = null

    // Media3's own frontier, which is already absolute and already contiguous
    // from the playhead — it stops at the first hole, so it needs no walk.
    // Overridden rather than left to MediaBackend's default for that reason:
    // this engine reports no ranges, and the default would read the empty list
    // as nothing buffered.
    override fun buffered(): Double = cachedBuffered

    override fun playbackRate(): Double = cachedRate

    override fun playbackRate(rate: Double): Unit {
        cachedRate = rate
        fireAndForget { player.setPlaybackSpeed(rate.toFloat()) }
    }

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
        cachedVolume = pendingVolume ?: player.volume
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
    // Whether adaptation is currently pinned by a caller. A cap is a
    // constraint on automatic behaviour; overriding a rung the viewer chose
    // themselves would be the player arguing with them.
    private var pinnedByCaller: Boolean = false

    // What to do about this item's dynamic range on this screen, reapplied
    // whenever the ladder changes.
    //
    // Not decided once at load: a live manifest gains and loses rungs, and a
    // decision taken against the first ladder stops matching the second. It is
    // recomputed on every tracks change, which is the same callback the cache
    // refreshes from.
    private fun applyHdrDecision() {
        when (val decision: HdrDecision = hdrDecision(
            levels = cachedQualityLevels,
            displayHdr = displayIsHdr,
            backendCanToneMap = canToneMapHdrToSdr,
            fallback = hdrFallback,
        )) {
            // An HDR screen, or nothing HDR to worry about. Any request left over
            // from a previous item is dropped rather than carried into this one.
            HdrDecision.AsIs -> engine.renderers.requestSdrToneMap = false

            // A rung the viewer chose themselves outranks the ceiling: a cap is a
            // constraint on automatic behaviour, and overriding an explicit choice
            // would be the player arguing with them.
            is HdrDecision.CapTo -> if (!pinnedByCaller) pinQuality(decision.level)

            // Idempotent — load already armed it. Re-armed here because a live
            // manifest can lose its last SDR rung mid-playback, and the request
            // then applies at the next codec configuration.
            HdrDecision.ToneMap -> engine.renderers.requestSdrToneMap = true

            // The consumer asked for a picture over no picture, and this is the
            // one branch where the colours are knowingly wrong.
            HdrDecision.PlayUnconverted -> Unit

            HdrDecision.Refuse -> refuseAsUnplayable()
        }
    }

    // No SDR rung, no conversion, and a consumer who chose not to show it wrong.
    //
    // Stopped as well as reported, because an error nothing acts on would leave
    // the washed-out picture on screen next to a message saying it cannot be
    // shown.
    private fun refuseAsUnplayable() {
        if (refusedAsUnplayable) return
        refusedAsUnplayable = true
        player.stop()
        bus.emit(CanonicalBackendEvent.ERROR, CoreErrorCodes.HDR_UNPLAYABLE)
    }

    override fun quality(level: QualityLevel?): Unit = fireAndForget {
        pinnedByCaller = level != null
        applySelection(level)
        refreshCache()
        // Lifting a pin hands the rung back to adaptation, which is exactly when
        // the display constraint has to come back — otherwise "automatic" means
        // "automatic, including into HDR this screen cannot show".
        if (level == null) applyHdrDecision()
    }

    private fun pinQuality(level: QualityLevel) = fireAndForget {
        applySelection(level)
        refreshCache()
    }

    private fun applySelection(level: QualityLevel?) {
        val override: TrackSelectionOverride? = level?.let(::overrideFor)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .apply { override?.let(::addOverride) }
            .build()
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
