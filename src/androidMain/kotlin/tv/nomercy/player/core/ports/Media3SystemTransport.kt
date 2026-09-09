// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.nomercy.player.core.plugin.BrowseNode
import tv.nomercy.player.core.plugin.BrowseTreeProvider

// The Android lock screen, notification and car display — all of them one
// session.
//
// There is exactly one, and that is the point of putting it here. The app has
// two owners today, a music service and a video service, each with its own
// MediaSessionCompat, and which of them a notification's buttons reach depends
// on which registered last. A session built by the library and released with the
// player cannot have that argument with itself.
@UnstableApi
internal class Media3SystemTransport(
    context: Context,
    // Injected rather than reached for, so a test can resolve a browse answer
    // on its own scheduler instead of waiting on a real thread pool.
    browseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Injected so a test can simulate the real OS rejecting a foreground-
    // service start (ForegroundServiceStartNotAllowedException) without
    // needing this process to genuinely be in a background-restricted
    // state — not reliably forceable from an instrumented test, since
    // instrumentation itself usually carries a foreground/recent-interaction
    // exemption. Defaults to the real call this class always made.
    private val requestForegroundService: (Context, Intent) -> Unit =
        { ctx, intent -> ctx.startForegroundService(intent) },
) : SystemTransport {

    private val appContext: Context = context.applicationContext

    private val bridge = TransportSimpleBasePlayer()

    // One thread, shared by every artwork fetch this session makes. Declared
    // before [session] on purpose: its initializer calls buildSession(),
    // which reads this — declared after session it is still null there
    // (confirmed live: NPE out of DataSourceBitmapLoader.loadBitmap).
    private val artworkLoaderExecutor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(java.util.concurrent.Executors.newSingleThreadExecutor())

    private val session: MediaLibrarySession = run {
        // The fixed [SESSION_ID] means Media3 throws "Session ID must be
        // unique" if a prior instance's own release() (app-side dispose() is
        // suspend, fired from a Compose DisposableEffect) has not completed
        // before this constructor runs — confirmed live: navigating back to a
        // watch screen and re-entering it crashed with exactly this exception
        // (real TV, 2026-08-11), because the old holder's async dispose lost
        // the race against the new holder's synchronous construction. Release
        // whatever is still published under this session's identity first, so
        // construction is idempotent regardless of which side wins the race.
        //
        // NOT routed through `publish(null)` first: that interim null reaches
        // NoMercyPlaybackService.reconcile() (a StateFlow collector on
        // Dispatchers.Main.immediate) before this constructor's own `init`
        // block publishes the real replacement, and reconcile() treats a null
        // session as "playback stopped" and calls stopSelf() — which then
        // raced the next line's startForegroundService(), producing a NEW
        // crash (ForegroundServiceDidNotStartInTimeException, confirmed live
        // switching from video to music on a real TV, 2026-08-11) in place of
        // the one this was fixing. Releasing the stale MediaSession directly
        // and letting the real publish() below carry the old→new transition
        // through reconcile() in one pass (it already removeSession()s
        // whatever was attached before addSession()ing the new one) avoids
        // the phantom "stopped" state entirely.
        // Guarded: release() is not confirmed idempotent under this race.
        runCatching { PlaybackForegroundSession.session.value?.release() }
        buildSession()
    }

    // The release above is not always enough on its own: it frees whatever
    // THIS process currently tracks as the active session, but a session from
    // an even earlier, already-abandoned construction (one that threw before
    // ever reaching publish()) can still hold the id in Media3's own registry
    // — confirmed live, real device, 2026-08-16 (the same "Session ID must be
    // unique" exception recurring through this exact constructor). One retry
    // after a short yield is a standard mitigation for a registry race like
    // this, not a guaranteed fix for whatever left the orphan behind.
    // The id stays fixed: music stopped keeping playback alive when it varied
    // per instance (reported live, 2026-08-16), because the foreground service
    // binds to the session it was promoted with. The duplicate-id crash this
    // was trying to solve is an orphan-release problem, not a naming one.
    private fun buildSession(): MediaLibrarySession {
        // The orphan, not the name. A session that was constructed and then
        // thrown away before publish() still holds SESSION_ID in Media3's
        // registry, and PlaybackForegroundSession only knows about published
        // ones — so releasing that was never enough. Every session this process
        // builds is tracked here and released before the next one, which is what
        // actually frees the id.
        lastBuilt?.let { orphan -> runCatching { orphan.release() } }
        lastBuilt = null

        // A MediaLibrarySession rather than a plain MediaSession, because a
        // Bluetooth head unit finds a player by enumerating BROWSABLE ones —
        // a session with no browse side is controllable once something else
        // starts it and invisible to the car's own player list, which is why
        // NoMercy never appeared beside Spotify on a head unit. MediaLibrarySession
        // is a MediaSession, so nothing that already held one notices.
        return MediaLibrarySession.Builder(appContext, bridge, TransportSessionCallback())
            .setId(SESSION_ID)
            .setBitmapLoader(DataSourceBitmapLoader(artworkLoaderExecutor, artworkDataSourceFactory()))
            .build()
            .also { built -> lastBuilt = built }
    }

    // Media3's own default BitmapLoader fetches artworkUri with a bare
    // HttpDataSource — no Authorization header — so a car, a Bluetooth head
    // unit or the lock screen got the title and the transport controls but
    // never the cover: the request reached a server that gates every image
    // the same way it gates everything else, and was refused (see
    // ArtworkAuthHeaders' own comment). The engine's own AuthHeaders is not
    // reachable here — it belongs to one playback backend, built later than
    // this session and released with it — so this reads the seam the app
    // installs once at startup, the same shape [mediaNotificationBranding]
    // already uses for the same forced-static-seam reason.
    private fun artworkDataSourceFactory(): DataSource.Factory {
        val headers: () -> Map<String, String> = { PlatformEnvironment.artworkAuthHeaders?.invoke().orEmpty() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val extra = headers()
                if (extra.isEmpty()) {
                    chain.proceed(request)
                } else {
                    val builder = request.newBuilder()
                    for ((name, value) in extra) {
                        if (request.header(name) == null) builder.header(name, value)
                    }
                    chain.proceed(builder.build())
                }
            }
            .build()
        return DefaultDataSource.Factory(appContext, OkHttpDataSource.Factory(client))
    }

    // Browse answers are suspend and a car asks on a binder thread it expects
    // back immediately, so every one of them is resolved off this scope and
    // handed over as a future. Off the main thread by default: a tree backed by
    // the network would otherwise resolve on the thread drawing the UI.
    private val browseScope = CoroutineScope(browseDispatcher + SupervisorJob())

    private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val pending: SettableFuture<LibraryResult<T>> = SettableFuture.create()
        browseScope.launch {
            // A tree that throws is a catalogue that failed to load, not a
            // reason to leave the car waiting on a future nobody completes.
            val answered: LibraryResult<T> = runCatching { block() }
                .getOrElse { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) }
            pending.set(answered)
        }
        return pending
    }

    // id -> what to run when the system reports that command pressed.
    // Rebuilt in full on every setCustomButtons call, same replace-the-whole-
    // map shape as TransportSimpleBasePlayer.setActions.
    private val customButtonHandlers = mutableMapOf<String, () -> Unit>()

    // The other half of the custom layout — the system reporting which of
    // those command ids was pressed, and the one place that needs to know
    // which app-level buttons exist at all so it can advertise them to a
    // connecting controller.
    private inner class TransportSessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            controllerSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            customButtonHandlers.keys.forEach { id -> available.add(SessionCommand(id, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.accept(
                available.build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            controllerSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            android.util.Log.d(
                "NMTransport",
                "customCommand=${customCommand.customAction} known=${customButtonHandlers.keys}",
            )
            customButtonHandlers[customCommand.customAction]?.invoke()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // The three questions a car, a watch or an assistant asks. Answered
        // from whatever tree the app installed; an app that installed none
        // answers a root with nothing under it, which is a true statement
        // about a player nobody gave a catalogue to.
        override fun onGetLibraryRoot(
            librarySession: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = answer {
            LibraryResult.ofItem(PlatformEnvironment.browseTree.root().toMediaItem(), params)
        }

        override fun onGetChildren(
            librarySession: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
            val tree: BrowseTreeProvider = PlatformEnvironment.browseTree
            val all: List<BrowseNode> = tree.children(parentId)
            LibraryResult.ofItemList(all.browsePage(page, pageSize).map { it.toMediaItem() }, params)
        }

        // A car tapped something. Nothing is added to the bridge — it is a
        // transport surface with no queue of its own, and handing it items it
        // cannot open would replace what is playing with silence. The empty
        // list is the honest answer to "what did you add"; the app plays the
        // selection through the same path its own screens use, and the
        // resulting metadata arrives back here as setNowPlaying.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val chosen: String? = mediaItems.firstOrNull()?.mediaId
            if (chosen != null) PlatformEnvironment.browseSelection?.invoke(chosen)
            return Futures.immediateFuture(emptyList())
        }
    }

    // Guards [release] against running twice: the pre-emptive release above
    // takes the OLD instance's own Media3 [MediaSession] out from under it,
    // and Media3 throws if `.release()` runs on an already-released session
    // when that old instance's own (suspend, slower) dispose() catches up.
    private var released = false

    init {
        // Published before the service is asked to start, not after — a
        // service whose first `onGetSession` call raced this init's own
        // assignment would answer null to the very query it started for.
        //
        // Starting the service itself does NOT happen here anymore — see
        // [setPlaybackState]'s own comment for why: this construction runs
        // the instant a plugin registers, which the app now does the moment
        // an item is merely SELECTED into the queue, well before its media
        // has loaded. `startForegroundService()` here started Android's
        // ~10s foreground-promotion clock at that same too-early moment,
        // and a slow load (network buffering) routinely outran it —
        // ForegroundServiceDidNotStartInTimeException, confirmed live, real
        // TV, 2026-08-12 (a track took ~35s to start decoding).
        PlaybackForegroundSession.publish(session)
    }

    private var servicePromotionRequested = false

    // The main-thread handle a real stop's unpublish/stopSelf is debounced
    // through — see [clear]'s own comment for why a stop needs a grace
    // window at all before it is allowed to actually tear anything down.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    // The same handle for the metadata-only blank — see [clearNowPlaying].
    private var pendingBlank: Runnable? = null

    // Held while playing and released the moment it stops.
    //
    // Without the wake lock the processor sleeps with the screen and audio
    // stutters and dies; without the wifi lock the radio drops to a power-saving
    // mode that a stream cannot keep up with. Both are acquired late and
    // released eagerly, because a lock still held after playback is a battery
    // complaint nobody can trace back here.
    private val wakeLock: PowerManager.WakeLock? =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)

    private val wifiLock: WifiManager.WifiLock? =
        (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG)

    override fun setNowPlaying(nowPlaying: NowPlaying) {
        // The replacement arrived, so the blank the transient cursor asked for
        // never has to happen — see [clearNowPlaying].
        pendingBlank?.let { mainHandler.removeCallbacks(it) }
        pendingBlank = null
        bridge.setNowPlaying(nowPlaying)
    }

    // Metadata only — no lock, no service, no session-publish state touched.
    // MediaSessionPlugin calls this for a transient or genuinely-empty cursor
    // (see its own comment), which happens on the way to almost every real
    // item too — anything here that reached into service lifecycle turned an
    // ordinary track change into a stop/republish cycle.
    //
    // Debounced, because MediaSessionPlugin.announce(null) fires on the way to
    // EVERY real item, not only on a genuinely exhausted queue (its own comment
    // says so). Blanking immediately means the head unit is told "nothing is
    // playing" between every two tracks — a phone notification survives that as
    // a flicker, a car display goes empty and a Bluetooth head unit redraws the
    // whole page. Holding the last item for a beat lets the replacement land
    // first; a clear that really was final still blanks, a beat later, and a
    // real stop does not come through here at all (see [clear]).
    override fun clearNowPlaying() {
        pendingBlank?.let { mainHandler.removeCallbacks(it) }
        val blank = Runnable {
            bridge.blank()
            pendingBlank = null
        }
        pendingBlank = blank
        mainHandler.postDelayed(blank, BLANK_DEBOUNCE_MS)
    }

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        // First real PLAYING push, not construction — this is the moment the
        // engine has actually been kicked (TransportController.play() emits
        // CoreEvents.Play, MediaSessionPlugin pushes PLAYING, right before
        // ctx.backend?.play() runs), so playWhenReady is about to go true on
        // the bridge within the same event turn. Starting the OS service
        // here instead of at construction collapses Android's foreground
        // grace window down to "right as Media3 has a reason to promote",
        // rather than starting the clock back when the item was merely
        // selected and possibly still buffering.
        // Bridge updated BEFORE the service is asked to promote — Media3's own
        // notification manager reads the player's current isPlaying off this
        // bridge the moment addSession() binds, and a stale "not playing"
        // read here is a promotion that never happens: it does not always get
        // a second chance from the later invalidateState(), and Android's 10s
        // grace window on startForegroundService() does not wait for one.
        // ForegroundServiceDidNotStartInTimeException, confirmed live, real
        // device, 2026-08-12, on an otherwise ordinary first play.
        bridge.setPlayback(state, positionMs)
        if (state == TransportPlaybackState.PLAYING) {
            // A play arriving while a just-issued stop is still in its grace
            // window (see [clear]) is the same track resuming, not a new
            // session to promote — cancel the pending teardown rather than
            // let it run out from under this play.
            pendingStop?.let { mainHandler.removeCallbacks(it) }
            pendingStop = null
        }
        // Requires an actual loaded item, not just the PLAYING label — a
        // toggle fired after a real stop and before the queue is reloaded
        // reports STATE_IDLE on the bridge regardless of this flag, and
        // Media3 will not promote an IDLE session to foreground: the
        // request times out and Android kills the app for it, confirmed
        // live, real device, 2026-08-12 (MusicPlayerFacade.togglePlayback
        // after a stop, no item ever reloaded). Falling through here still
        // leaves playWhenReady=true on the bridge for when a real item does
        // load; it only withholds the doomed promotion attempt.
        if (state == TransportPlaybackState.PLAYING && !servicePromotionRequested && bridge.hasItem) {
            // clear() (a real stop, not a pause) unpublishes this session so
            // the service demotes out of foreground — see its own comment.
            // Republish here so the NEXT play after a stop has a session for
            // the service's onGetSession to answer, exactly like the initial
            // publish in init.
            if (PlaybackForegroundSession.session.value !== session) {
                PlaybackForegroundSession.publish(session)
            }
            // Latched only on success — a rejected start (e.g. this device was
            // backgrounded, mirroring another device's session) must retry on
            // the next genuine PLAYING transition rather than give up for
            // this instance's whole lifetime.
            servicePromotionRequested = startPlaybackService()
        }
        holdLocks(state == TransportPlaybackState.PLAYING)
    }

    override fun setActionHandlers(actions: TransportActions) {
        bridge.setActions(actions)
    }

    override fun setDeviceVolume(percent: Int) {
        bridge.setRemoteVolume(percent)
    }

    override fun setRoutingControllerId(routingControllerId: String?) {
        bridge.setRoutingControllerId(routingControllerId)
    }

    // Builds Media3's own CommandButton layout from whatever generic buttons
    // the app supplied — this library never asks what any of them mean.
    // iconKey resolution goes through PlatformEnvironment the same way
    // MediaNotificationBranding's icon does; a key with nothing installed to
    // resolve it draws CommandButton's own undefined icon rather than one
    // this library invented.
    override fun setCustomButtons(buttons: List<CustomTransportButton>) {
        customButtonHandlers.clear()
        val resolver = PlatformEnvironment.customTransportButtonIconResolver

        val layout = buttons.map { button ->
            customButtonHandlers[button.id] = button.onPress
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName(button.label)
                .setSessionCommand(SessionCommand(button.id, Bundle.EMPTY))
                .setCustomIconResId(resolver?.resolve(button.iconKey) ?: 0)
                .setEnabled(true)
                .build()
        }
        session.setCustomLayout(layout)

        // The notification's controller connects when the session is built, before
        // any button exists, so onConnect granted it none of these commands and
        // every press was rejected — the button drew and did nothing. Re-grant on
        // every change, for the controllers already attached.
        val granted = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        customButtonHandlers.keys.forEach { id -> granted.add(SessionCommand(id, Bundle.EMPTY)) }
        val commands = granted.build()
        // The notification's own controller is NOT in connectedControllers —
        // Media3 keeps it separate, and it is the one the notification button
        // dispatches through, so granting only the ordinary controllers left the
        // press rejected exactly as before.
        val controllers = session.connectedControllers +
            listOfNotNull(session.mediaNotificationControllerInfo)
        controllers.forEach { controller ->
            runCatching {
                session.setAvailableCommands(
                    controller,
                    commands,
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                )
            }
        }
    }

    // A real stop, not a pause — the viewer closed the player rather than
    // merely paused it. Blanking the bridge alone left the foreground
    // service (and its notification) pinned showing whatever it last had,
    // because nothing here ever told NoMercyPlaybackService.reconcile() that
    // playback had genuinely ended (confirmed live: stop left a stale
    // notification behind, real device, 2026-08-12). Unpublishing does that
    // — reconcile() sees null and calls stopSelf(), which removes the
    // notification.
    //
    // The unpublish itself is debounced rather than immediate: a stop
    // followed within a beat by a fresh play — confirmed live, real device,
    // 2026-08-12, a stop/play/stop burst inside ~1.1s while backgrounding
    // the app — otherwise tears the service down before Media3 has had a
    // chance to call startForeground() on the session that stop's own
    // republish just handed it, throwing
    // ForegroundServiceDidNotStartInTimeException regardless of how briefly
    // the service actually lived. [setPlaybackState]'s PLAYING branch
    // cancels this if a play lands first, so a genuine resume never sees
    // the notification blink off and back on.
    override fun clear() {
        // A real stop blanks now rather than in a beat: the debounce exists for
        // the transient cursor between two tracks, and this is not that.
        pendingBlank?.let { mainHandler.removeCallbacks(it) }
        pendingBlank = null
        bridge.blank()
        customButtonHandlers.clear()
        session.setCustomLayout(emptyList())
        holdLocks(false)
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        val stop = Runnable {
            if (PlaybackForegroundSession.session.value === session) {
                PlaybackForegroundSession.publish(null)
            }
            servicePromotionRequested = false
            pendingStop = null
        }
        pendingStop = stop
        mainHandler.postDelayed(stop, STOP_DEBOUNCE_MS)
    }

    // released is only ever set by this instance's OWN release() below — the
    // constructor's pre-emptive takeover a few lines up calls .release() on
    // the raw MediaLibrarySession it reads out of PlaybackForegroundSession,
    // which has no reference back to whichever Media3SystemTransport wrapper
    // used to own it, so a takeover can never flip that wrapper's own
    // `released` this way. Comparing session identity against the currently
    // published one catches that case too: once a NEWER instance's own
    // constructor has published a different session, this one is exactly as
    // dead as if it had released itself, whether or not that ever happened.
    // Confirmed live, real device, 2026-09-09: leaving a video screen while
    // passively mirroring music left `released` false on the superseded
    // instance forever, so liveTransport() kept handing it back and no new
    // Media3SystemTransport — and no notification — was ever built again.
    override val isReleased: Boolean
        get() = released || PlaybackForegroundSession.session.value !== session

    override fun release() {
        if (released) return
        released = true
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        pendingBlank?.let { mainHandler.removeCallbacks(it) }
        pendingBlank = null
        browseScope.cancel()
        holdLocks(false)
        // Publish before releasing the Media3 object, so the service's own
        // reconcile sees the `null` transition and removeSession()s a
        // session that still answers, rather than one already torn down
        // underneath it.
        if (PlaybackForegroundSession.session.value === session) {
            PlaybackForegroundSession.publish(null)
        }
        // Guarded: the constructor's own pre-emptive release (above) may already
        // have released this exact object from a newer instance — not confirmed
        // idempotent, so this is a no-op either way if Media3 already threw.
        runCatching { session.release() }
    }

    // `startForegroundService`, not `startService` — a background-started
    // service is killed within seconds unless it promotes itself to
    // foreground almost immediately, which is exactly what
    // [NoMercyPlaybackService.reconcile] does the moment it sees this
    // session over `addSession`. This project's minSdk (29) is already past
    // the API 26 floor `startForegroundService` needs, so there is no older
    // path to fall back to.
    private fun startPlaybackService(): Boolean {
        // A backgrounded process is not allowed to start one, and the platform
        // answers with ForegroundServiceStartNotAllowedException — which is
        // fatal, not ignorable. A device MIRRORING a session playing elsewhere
        // publishes state while sitting on a home screen with nothing of its own
        // playing, and every such publish killed the app: a television crashed
        // and relaunched on every frame it mirrored (measured 2026-08-17).
        //
        // Swallowed rather than pre-checked: the allowance depends on state only
        // the platform knows, and the service exists to keep OUR OWN audio
        // alive. A process that is not allowed to start it has no audio to keep.
        //
        // Returns whether the start was actually accepted — the caller must not
        // latch a one-shot "already tried" flag on a swallowed failure, or a
        // rejection while merely mirroring a passive session permanently
        // forfeits the notification for the rest of this instance's life, even
        // once the app is later foregrounded and the same start would succeed.
        return runCatching {
                requestForegroundService(appContext, Intent(appContext, NoMercyPlaybackService::class.java))
            }
            .onFailure { failure ->
                android.util.Log.w("Media3SystemTransport", "playback service not started: ${failure.message}")
            }
            .isSuccess
    }

    private fun holdLocks(hold: Boolean) {
        // Guarded on the lock's own view of itself rather than on a flag here.
        // A wake lock released twice throws, and the state that matters is the
        // one Android holds.
        if (hold) {
            if (wakeLock?.isHeld == false) wakeLock.acquire(WAKE_TIMEOUT_MS)
            if (wifiLock?.isHeld == false) wifiLock.acquire()
        } else {
            if (wakeLock?.isHeld == true) wakeLock.release()
            if (wifiLock?.isHeld == true) wifiLock.release()
        }
    }

    private companion object {
        const val SESSION_ID = "nomercy-player"

        // Process-wide: the id is process-wide too.
        @Volatile
        private var lastBuilt: MediaLibrarySession? = null

        const val WAKE_TAG = "nomercy:playback"
        const val WIFI_TAG = "nomercy:streaming"

        // A ceiling rather than an intent. Android kills a wake lock held past
        // its timeout, which is the behaviour wanted if this process is ever
        // torn down without release() running — a lock held forever is a phone
        // that does not sleep.
        const val WAKE_TIMEOUT_MS = 4L * 60L * 60L * 1_000L

        // Long enough to swallow a same-track pause/resume or focus-loss
        // blip, short enough that a genuine stop still clears the
        // notification promptly — see [clear]'s own comment.
        const val STOP_DEBOUNCE_MS = 800L

        // Wider than the stop debounce on purpose: the gap this covers is a
        // queue REPLACE resolving its next item, which can wait on the network,
        // and the cost of guessing too long is a stale title for a beat while
        // the cost of guessing too short is the empty car display this exists
        // to stop.
        const val BLANK_DEBOUNCE_MS = 3_000L
    }
}
