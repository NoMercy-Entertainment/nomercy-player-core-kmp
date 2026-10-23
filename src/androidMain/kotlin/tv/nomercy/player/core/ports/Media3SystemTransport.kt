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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    // id -> what to run when the system reports that command pressed.
    // Rebuilt in full on every setCustomButtons call, same replace-the-whole-
    // map shape as TransportSimpleBasePlayer.setActions.
    private val customButtonHandlers = mutableMapOf<String, () -> Unit>()

    private val controls = object : TransportControls {
        override val commandIds: Set<String> get() = customButtonHandlers.keys.toSet()

        override fun press(id: String) {
            customButtonHandlers[id]?.invoke()
        }
    }

    // The process-wide session this transport's bridge is attached to. Not
    // built or released here: a car bound to it must outlive any one playback.
    private val session: MediaLibrarySession =
        SharedLibrarySession.attach(appContext, bridge, controls, browseDispatcher)

    // What the session's custom layout last drew, so an unchanged set of buttons
    // is not pushed again. Nothing is drawn before the first call.
    private data class ButtonShape(val id: String, val label: String, val iconKey: String)

    private var customLayoutShape: List<ButtonShape> = emptyList()

    // Guards [release] against running twice.
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

    // See SystemTransport.setNotificationEligible. True until a caller says
    // otherwise, so an ordinary player never has to opt in.
    private var notificationEligible = true

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
        // Gating this on bridge.isVolumeRemoteNow was wrong, and it cost the
        // notification for every cast remote and every passively mirroring
        // Connect client — the exact sessions a viewer most expects to find
        // in the shade, because the playback they control is in another room
        // and this notification is the only handle on it. The service is what
        // posts that notification, so withholding it removed the widget while
        // leaving the session (and its volume-key routing) intact: playing on
        // the TV, remote open in the app, nothing in the shade. Reported
        // live, real phone, 2026-09-20.
        //
        // The real distinction is not local-versus-remote. It is whether this
        // session is a now-playing at all: a bare volume claim published only
        // so a hardware key has somewhere to route is not, and says so with
        // setNotificationEligible(false). Everything else is.
        val eligibleForLocalPromotion = bridge.hasItem && notificationEligible
        val shouldPromoteForegroundService =
            state == TransportPlaybackState.PLAYING && !servicePromotionRequested && eligibleForLocalPromotion
        if (shouldPromoteForegroundService) {
            // clear() (a real stop, not a pause) unpublishes this session so
            // the service demotes out of foreground — see its own comment.
            // Republish here so the NEXT play after a stop has a session for
            // the service's onGetSession to answer, exactly like the initial
            // publish in init.
            if (isCurrent && PlaybackForegroundSession.session.value !== session) {
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

    override fun setNotificationEligible(eligible: Boolean) {
        // Only ever consulted on the way IN to a promotion, so this must be
        // set before the first PLAYING push it should apply to. A session
        // turned ineligible after it was already promoted keeps the service
        // it has: taking one down is what [clear] and [release] are for, and
        // doing it here would demote a live notification on a metadata edit.
        notificationEligible = eligible
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
        buttons.forEach { button -> customButtonHandlers[button.id] = button.onPress }
        // A superseded transport must not draw over the one that replaced it.
        if (!SharedLibrarySession.isAttached(bridge)) return
        // Handlers are always current; the layout and the re-grant below are two
        // notification updates each, so they go out only when a button changed.
        val shape = buttons.map { ButtonShape(it.id, it.label, it.iconKey) }
        if (shape == customLayoutShape) return
        customLayoutShape = shape
        val resolver = PlatformEnvironment.customTransportButtonIconResolver

        val layout = buttons.map { button ->
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
        if (customLayoutShape.isNotEmpty()) {
            customLayoutShape = emptyList()
            if (isCurrent) session.setCustomLayout(emptyList())
        }
        holdLocks(false)
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        val stop = Runnable {
            if (isCurrent && PlaybackForegroundSession.session.value === session) {
                PlaybackForegroundSession.publish(null)
            }
            servicePromotionRequested = false
            pendingStop = null
        }
        pendingStop = stop
        mainHandler.postDelayed(stop, STOP_DEBOUNCE_MS)
    }

    // A newer transport attaching its bridge supersedes this one without calling
    // release() here, so the released flag alone stays false for good (device,
    // 2026-09-09).
    private val isCurrent: Boolean get() = SharedLibrarySession.isAttached(bridge)

    override val isReleased: Boolean
        get() = released || !isCurrent || PlaybackForegroundSession.session.value !== session

    override fun release() {
        if (released) return
        released = true
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        pendingBlank?.let { mainHandler.removeCallbacks(it) }
        pendingBlank = null
        bridge.cancelPendingUpdate()
        holdLocks(false)
        val owned: Boolean = isCurrent && PlaybackForegroundSession.session.value === session
        // Detached first, so the service can dispose an idle session on the null.
        SharedLibrarySession.detach(bridge)
        if (owned) PlaybackForegroundSession.publish(null)
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

// The artwork-loader executor's own lifecycle, split out so it carries no
// Android Context dependency and a host test can prove [release] actually
// shuts it down — see [SharedLibrarySession], which owns it.
internal class ArtworkLoaderExecutor {

    val service: ListeningExecutorService =
        MoreExecutors.listeningDecorator(
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "nomercy-artwork-loader").apply { isDaemon = true }
            },
        )

    private var released = false

    fun release() {
        if (released) return
        released = true
        service.shutdown()
    }
}
