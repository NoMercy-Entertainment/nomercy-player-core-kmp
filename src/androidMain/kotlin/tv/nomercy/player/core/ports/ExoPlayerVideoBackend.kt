// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.media.QualityDescriptor
import tv.nomercy.player.core.events.SubtitleCue
import tv.nomercy.player.core.events.SubtitleCueChange

private const val MILLIS_PER_SECOND = 1000.0
private const val TIME_UPDATE_INTERVAL_MS = 250L

// Mirrors the web trio's HLS_EXT_RE: `.m3u8` then a query, fragment, or end of
// URL — a plain endsWith miss on `master.m3u8#t=30` wrongly disabled tunneling.
internal val HLS_EXT_RE = Regex("""\.m3u8(?:[?#]|$)""", RegexOption.IGNORE_CASE)

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
    private val trackSelector: DefaultTrackSelector = DefaultTrackSelector(context).apply {
        // A rung the decoder cannot play is not a rung.
        //
        // ExoPlayer's default is to select a format that EXCEEDS the renderer's
        // capabilities when nothing else is preferred, on the reasoning that a
        // best effort beats refusing to start. On a ladder with a supported
        // alternative that is the wrong trade: Sintel's manifest carries a PQ
        // variant beside an SDR one at the same resolution, the phone reported
        // `format_supported=NO_EXCEEDS_CAPABILITIES` for the PQ rung, and
        // playback died with ERROR_CODE_DECODING_FAILED rather than dropping to
        // the SDR rung sitting next to it.
        //
        // Not an HDR rule. Any rung this device cannot decode — a profile, a
        // level, a resolution — is now skipped rather than attempted, which is
        // the class rather than the instance.
        // And a cap the codec string cannot lie its way past.
        //
        // The rule above trusts what the manifest declares, and Sintel's declares
        // `avc1.4D401E` — Main profile at LEVEL 3.0, which tops out at 720x576 —
        // for all four rungs including the two at 3840x1635. The device reports
        // that format as supported, because at level 3.0 it is; the decoder then
        // receives 4K frames and dies with ERROR_CODE_DECODING_FAILED on the
        // first one. Capability filtering cannot see a lie of that shape.
        //
        // The viewport can. Selecting no rung larger than the screen is what
        // every Android player does anyway — decoding 4K for a 1080-wide panel
        // is heat and battery for pixels nobody sees — and it survives a manifest
        // whose codec strings are wrong, which is the case that actually bit.
        parameters = buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(false)
            .build()
    }

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

    // Riding out a server that went away mid-playback — a host restart, a LAN
    // drop, a Wi-Fi roam. Media3 reports these as fatal source errors, but
    // nothing about the media is broken: the bytes are back the moment the
    // server is. See the transient branch in onPlayerError.
    private var networkRetryAttempt: Int = 0
    private var networkRecoveryJob: Job? = null
    private var positionBeforeNetworkLoss: Long = 0L
    // Set once the ladder is spent, so a device that regains connectivity later
    // still rebuilds the session instead of leaving the viewer on a dead error.
    private var awaitingNetworkReturn: Boolean = false
    // Whether this outage started at the connection level, which is what tells
    // a restarting server's 404 apart from a video that is genuinely gone.
    private var sawConnectionFailure: Boolean = false

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val recovering = MutableStateFlow(false)

    /**
     * True while the reconnect ladder is riding out a source the server stopped
     * serving.
     *
     * The engine is the first thing in an app to learn the origin is gone: it
     * asks for bytes constantly, while a realtime socket behind the same tunnel
     * can stay up long after the server behind it died. A host watches this to
     * probe reachability on the engine's evidence rather than waiting for a
     * socket that may never drop — which is why a server restart could leave a
     * TV on a dead player and never raise the server-offline screen.
     */
    public val isRecoveringFromOutage: StateFlow<Boolean> = recovering.asStateFlow()

    /**
     * Whether this session means to play, as opposed to having been paused.
     *
     * Survives buffering, so it separates "the bytes stopped" from "the viewer
     * paused before any of this" — which is what a host needs to decide whether
     * an outage screen owes playback back when it leaves.
     */
    public val isPlaybackIntended: Boolean
        get() = runCatching { player.playWhenReady }.getOrDefault(false)

    // Once per player. A tracks change fires on every rung switch and a
    // viewer does not need the same refusal repeated for the whole film.
    private var reportedUnsupported: Boolean = false

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

    private val engine: ExoEngine =
        buildEngine(context, authHeaders, trackSelector, equaliser) { declared -> declaredVariants = declared }

    // The ladder the master playlist declared, kept so a rung can be told its own
    // dynamic range. Media3 will not say: Format.colorInfo is null for an HLS
    // variant until its decoder is configured, so every rung of a PQ ladder read
    // back as SDR.
    @Volatile
    private var declaredVariants: List<QualityDescriptor> = emptyList()

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
            // Which rung was taken, and whether anything forced it.
            //
            // A decoder that dies partway through reports the format it choked
            // on and nothing about how that format came to be selected. The
            // difference between "auto picked a rung the constraints should
            // have excluded" and "an override forced one past them" is the
            // whole diagnosis, and neither is visible from the failure.
            override fun onTracksChanged(tracks: Tracks) {
                val chosen: Format? = tracks.groups
                    .filter { group -> group.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).mapNotNull { index ->
                        if (group.isTrackSelected(index)) group.getTrackFormat(index) else null
                    } }
                    .firstOrNull()
                val overrides: Int = player.trackSelectionParameters
                    .overrides.keys.count { group -> group.type == C.TRACK_TYPE_VIDEO }
                Log.i(
                    "nm-rung",
                    "video rung: ${chosen?.width}x${chosen?.height} ${chosen?.codecs} " +
                        "bitrate=${chosen?.bitrate} overrides=$overrides " +
                        "viewport=${trackSelector.parameters.viewportWidth}x" +
                        "${trackSelector.parameters.viewportHeight}",
                )
                reportUnsupportedVideo(tracks)
            }

            // A stream this device cannot decode is SILENT in Media3: the
            // renderer is dropped, the audio plays on, and nothing is thrown.
            // Measured on an SM-A137F against an HEVC Main10 rung — four
            // `NoSupport [codec.profileLevel, hvc1.2.4.L120.B0]` lines in
            // logcat, no ExoPlaybackException, and a black picture the viewer
            // has no explanation for.
            private fun reportUnsupportedVideo(tracks: Tracks) {
                val video: List<Tracks.Group> = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isEmpty()) return
                // Every rung refused, not merely the one that was picked: a
                // manifest with a playable alternative is an ABR decision, and
                // reporting that would cry wolf on every adaptive stream.
                val anyPlayable: Boolean = video.any { group ->
                    (0 until group.length).any { index -> group.isTrackSupported(index) }
                }
                if (anyPlayable || reportedUnsupported) return

                reportedUnsupported = true
                bus.emit(CanonicalBackendEvent.ERROR, CoreErrorCodes.CODEC_UNSUPPORTED)
            }

            override fun onPlaybackStateChanged(state: Int) {
                refreshCache()
                when (state) {
                    Player.STATE_BUFFERING -> bus.emit(CanonicalBackendEvent.WAITING)
                    Player.STATE_READY -> {
                        // Bytes are flowing again — retire any in-flight
                        // reconnect and hand the full backoff ladder back.
                        if (networkRetryAttempt != 0) {
                            Log.i(
                                "nm-video-backend",
                                "Playback recovered after $networkRetryAttempt reconnect attempt(s)",
                            )
                        }
                        resetOutageLadder()
                        positionBeforeNetworkLoss = 0L

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
                if (rideOutSourceOutage(error)) return
                bus.emit(CanonicalBackendEvent.ERROR, error.errorCodeName)
            }

            // The cues of whichever text track is selected, as the engine
            // crosses them. Media3 has decoded and timed them all along; nothing
            // asked for them, so a viewer who turned subtitles on watched a film
            // with the track playing and no words on screen.
            //
            // Every change, including the empty one. Media3 reports an empty
            // group at the end of a cue and when the track is deselected, and
            // that is precisely the signal a renderer needs to wipe what it is
            // drawing — swallowing it would leave the last line of dialogue on
            // the picture for the rest of the film.
            override fun onCues(cueGroup: CueGroup) {
                announceCues(ExoCueMapper.cuesOf(cueGroup.cues))
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
            sourceIsHls = HLS_EXT_RE.containsMatchIn(url),
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

    /**
     * A server that went away is an outage to ride out, not a dead file.
     *
     * Tearing the session down with an error overlay is what made a brief
     * server blip read to a viewer as a corrupt film. Re-prepare from the
     * position that was lost, on a backoff, and hold the UI in its waiting
     * state so it reads as reconnecting rather than dead. Only once the ladder
     * is spent does the failure become real and the error surface.
     *
     * Returns whether it handled the error, so the caller does not also report
     * one the viewer is about to stop seeing.
     */
    private fun rideOutSourceOutage(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) {
            sawConnectionFailure = true
        }

        if (!SourceOutage.isTransient(error.errorCode)) return false

        val limit: Int = SourceOutage.retryLimitFor(
            error.errorCode,
            SourceOutage.httpStatusOf(error),
            sawConnectionFailure,
        )
        val attempt: Int = networkRetryAttempt
        if (attempt >= limit) {
            // The ladder is spent. The session stays revivable rather than
            // dead: the error surfaces so the viewer sees what happened, and
            // the connectivity callback rebuilds playback if this device gets a
            // network back later.
            awaitingNetworkReturn = true
            ensureNetworkCallback()
            recovering.value = false
            return false
        }

        // Captured on the FIRST failure only. Every later prepare() has already
        // reset the timeline, so reading it again would resume from zero.
        if (attempt == 0) {
            positionBeforeNetworkLoss = runCatching { player.currentPosition }.getOrDefault(0L)
        }

        // An outage on THIS device's side is not evidence that the server is
        // gone, so it must not spend the give-up budget — a two-minute Wi-Fi
        // drop would burn the whole ladder without a single attempt ever having
        // a route to try. Hold at the slowest rung and let the connectivity
        // callback take over the moment a network is back.
        val deviceOffline: Boolean = !hasNetwork()
        if (!deviceOffline) networkRetryAttempt = attempt + 1
        ensureNetworkCallback()

        Log.w(
            "nm-video-backend",
            "Transient source error (${error.errorCodeName}) — reconnect attempt " +
                "${attempt + 1}/$limit in ${SourceOutage.BACKOFF_MS[attempt]}ms " +
                "from ${positionBeforeNetworkLoss}ms" +
                if (deviceOffline) " (device offline, budget held)" else "",
        )
        recovering.value = true
        bus.emit(CanonicalBackendEvent.WAITING)
        reconnect(SourceOutage.BACKOFF_MS[attempt])
        return true
    }

    private fun reconnect(delayMs: Long) {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = main.launch {
            if (delayMs > 0) delay(delayMs)
            runCatching {
                // playWhenReady survives a source error but is cleared by
                // pause(), so reading it HERE rather than at failure time is
                // what lets an intervening pause — the viewer's, or an outage
                // screen's — suppress the auto-resume instead of being
                // overridden by it.
                val shouldResume: Boolean = player.playWhenReady
                player.prepare()
                if (positionBeforeNetworkLoss > 0) player.seekTo(positionBeforeNetworkLoss)
                if (shouldResume) player.play()
            }.onFailure { e ->
                Log.e("nm-video-backend", "Reconnect attempt failed: ${e.message}", e)
            }
        }
    }

    /**
     * Whether the device itself is on a network right now. Deliberately "is
     * there a default network", not "is the internet validated": a NoMercy
     * server is usually a box on the same LAN, and a Wi-Fi network with no
     * internet route still reaches it.
     */
    private fun hasNetwork(): Boolean {
        val manager: ConnectivityManager = connectivityManager ?: return true
        return runCatching { manager.activeNetwork != null }.getOrDefault(true)
    }

    /**
     * Watches for the device coming back online so an outage longer than the
     * backoff ladder still ends in playback rather than a dead error. Without
     * it, a Wi-Fi drop that outlasts the ladder leaves the session
     * unrecoverable until the viewer backs out and picks the episode again.
     */
    private fun ensureNetworkCallback() {
        if (networkCallback != null) return
        val manager: ConnectivityManager = connectivityManager ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.launch {
                    if (networkRetryAttempt == 0 && !awaitingNetworkReturn) return@launch

                    Log.i("nm-video-backend", "Connectivity returned — reconnecting now")
                    // A fresh outage deserves the whole ladder: the attempts
                    // spent waiting for the network to come back say nothing
                    // about whether the server answers now.
                    resetOutageLadder()
                    reconnect(0L)
                }
            }
        }

        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { e ->
            Log.w("nm-video-backend", "Could not register network callback: ${e.message}")
        }
    }

    private fun releaseNetworkCallback() {
        val manager: ConnectivityManager = connectivityManager ?: return
        networkCallback?.let { callback ->
            runCatching { manager.unregisterNetworkCallback(callback) }
                .onFailure { e ->
                    Log.w("nm-video-backend", "Could not unregister network callback: ${e.message}")
                }
        }
        networkCallback = null
    }

    /** Hands the full backoff budget back, so a later unrelated drop does not inherit a spent one. */
    private fun resetOutageLadder() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        networkRetryAttempt = 0
        awaitingNetworkReturn = false
        sawConnectionFailure = false
        recovering.value = false
    }

    override suspend fun load(url: String, opts: LoadOptions): Unit = onMain {
        bus.emit(CanonicalBackendEvent.LOAD_START, url)
        announcedCanPlay = false
        refusedAsUnplayable = false
        // A new source is a new session: the rungs the previous one spent say
        // nothing about whether this one can be fetched.
        resetOutageLadder()
        positionBeforeNetworkLoss = 0L
        // The previous item's last line, taken off the picture before the next
        // one's first frame arrives. The web backend does this in unload() for
        // the same reason.
        announceCues(emptyList())
        // Armed BEFORE prepare, because a codec reads its colour-transfer request
        // when it is configured and the ladder is not known until after. Arming it
        // on an SDR display costs nothing on SDR content: the factory only asks
        // when the format actually reaching the decoder carries an HDR transfer,
        // which is precisely the case CapTo has already ruled out.
        engine.renderers.requestSdrToneMap = !displayIsHdr && canToneMapHdrToSdr
        applyTunneling(url)
        // What the caller asked to be sent, sent.
        //
        // `LoadOptions.headers` is part of the backend contract and the mpv
        // backend has always honoured it; this one dropped it, so a signed
        // library played on the desktop and failed here with
        // ERROR_CODE_IO_BAD_HTTP_STATUS -- a 401 arriving as "unknown error"
        // over a black picture. Photographed on a phone against a real server.
        //
        // Through the same provider a host uses, because the interceptor
        // beneath already applies it to every manifest and segment request and
        // a second path would be a second thing to keep in step. A host that
        // set its own provider keeps it when a load carries no headers.
        if (opts.headers.isNotEmpty()) {
            val carried: Map<String, String> = opts.headers
            authHeaders.provider = { carried }
        }
        // The position goes ON the item, not after prepare(). Seeking
        // afterwards works only because a finished file has every segment: the
        // engine loads from zero, then jumps. A live transcode has exactly the
        // part the encoder has written, so a resumed episode fetched segment 0
        // from a session that starts minutes later and waited on it forever.
        player.setMediaItem(MediaItem.fromUri(url), opts.startPositionMs.coerceAtLeast(0L))
        // prepare, not play: starting is a separate decision above, and an
        // engine that started on its own would ignore a refused beforePlay.
        player.prepare()
    }

    override suspend fun play(): Unit = onMain {
        bus.emit(CanonicalBackendEvent.PLAY)
        // An idle player ignores play(): a source that died after the ladder ran
        // out leaves the engine in STATE_IDLE with no timeline to resume, so the
        // press did nothing at all and the player read as dead. The media item
        // is still there, so re-preparing resumes at the second it was lost
        // rather than rebuilding from the last position anything was told about.
        if (awaitingNetworkReturn && player.playbackState == Player.STATE_IDLE) {
            Log.w("nm-video-backend", "play() after a spent reconnect ladder — re-preparing the source")
            resetOutageLadder()
            player.playWhenReady = true
            reconnect(0L)
            return@onMain
        }
        player.play()
    }

    override fun pause(): Unit = fireAndForget { player.pause() }

    override fun stop(): Unit = fireAndForget {
        stopTicking()
        // A pending reconnect would otherwise fire its prepare()/play() after
        // this stop and quietly resurrect playback — under the outage screen,
        // in the very case that asked us to stop.
        resetOutageLadder()
        positionBeforeNetworkLoss = 0L
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
        cachedQualityLevels = ExoTrackMapper.qualityLevels(tracks).map(::withDeclaredRange)
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

    // The manifest's word about a rung's dynamic range, over the engine's.
    //
    // Matched on height, which is what a variant and its Format agree on. A rung
    // the manifest never declared keeps whatever the engine said, because an
    // unmatched rung is a progressive file or a local one and there is no
    // playlist to be more right than the decoder.
    private fun withDeclaredRange(level: QualityLevel): QualityLevel {
        val declared: QualityDescriptor = declaredVariants
            .firstOrNull { it.height == level.height } ?: return level

        return level.copy(dynamicRange = declaredRangeOf(declared.dynamicRange))
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
            // Said here as well as waited for. Media3 does report an empty group
            // after a deselection, but not always before the next frame is
            // drawn, and the gap is the one a viewer notices: captions turned
            // off with the last line still on the picture.
            announceCues(emptyList())
        } else {
            selectByTypeOnMain(C.TRACK_TYPE_TEXT, track.id)
        }
        refreshCache()
    }

    // One place the cue channel is written, so the language always travels with
    // the cues and cannot be attached at one emit site and forgotten at another.
    private fun announceCues(cues: List<SubtitleCue>) {
        bus.emit(
            CanonicalBackendEvent.SUBTITLE_CUE,
            SubtitleCueChange(cues = cues, language = cachedSubtitleTrack?.language),
        )
    }

    private fun selectByType(type: Int, id: String): Unit = fireAndForget {
        selectByTypeOnMain(type, id)
        refreshCache()
    }

    // By id, because that is what the track the caller was handed carries — the
    // caller never sees this engine's numbering. The id itself IS that
    // numbering now (ExoTrackMapper's positional "audio:n"/"text:n"), because
    // matching against the raw Format.id below it used to resolve to was
    // wrong: HLS renditions routinely share one non-null Format.id across every
    // language variant, so firstOrNull matched the wrong track and every row in
    // the picker compared equal to "selected" (confirmed live, real TV,
    // 2026-08-15). Parsed back out here rather than passed as a second
    // parameter, so ExoTrackMapper stays the only place that owns the format.
    private fun selectByTypeOnMain(type: Int, id: String) {
        val flatIndex: Int = id.substringAfterLast(':').toIntOrNull() ?: return
        val located: Pair<Tracks.Group, Int> = player.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map { group to it } }
            .getOrNull(flatIndex)
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
    override fun release(): Unit = fireAndForget {
        stopTicking()
        resetOutageLadder()
        releaseNetworkCallback()
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
    //
    // Guarded: a call queued right before release() (also fireAndForget) can
    // run after it — a seek/pause reaching a torn-down player throws
    // IllegalStateException, otherwise uncaught and fatal on this scope.
    private fun fireAndForget(block: () -> Unit) {
        main.launch {
            runCatching {
                block()
                refreshCache()
            }.onFailure { e -> Log.w("nm-video-backend", "fireAndForget after teardown: ${e.message}") }
        }
    }
}
