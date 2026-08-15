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
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// The Android lock screen, notification and car display — all of them one
// session.
//
// There is exactly one, and that is the point of putting it here. The app has
// two owners today, a music service and a video service, each with its own
// MediaSessionCompat, and which of them a notification's buttons reach depends
// on which registered last. A session built by the library and released with the
// player cannot have that argument with itself.
@UnstableApi
internal class Media3SystemTransport(context: Context) : SystemTransport {

    private val appContext: Context = context.applicationContext

    private val bridge = TransportSimpleBasePlayer()

    private val session: MediaSession = run {
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
        // Guarded: whatever raced this constructor to publish first may have
        // already released this same object through the same pre-emptive
        // call, on the same "whichever side wins the race" reasoning this
        // comment already describes — Media3's MediaSession.release() is not
        // confirmed idempotent, and the sibling guard on the last line of
        // [release] exists for exactly this uncertainty.
        runCatching { PlaybackForegroundSession.session.value?.release() }
        MediaSession.Builder(appContext, bridge)
            .setId(SESSION_ID)
            .setCallback(TransportSessionCallback())
            .build()
    }

    // id -> what to run when the system reports that command pressed.
    // Rebuilt in full on every setCustomButtons call, same replace-the-whole-
    // map shape as TransportSimpleBasePlayer.setActions.
    private val customButtonHandlers = mutableMapOf<String, () -> Unit>()

    // The other half of the custom layout — the system reporting which of
    // those command ids was pressed, and the one place that needs to know
    // which app-level buttons exist at all so it can advertise them to a
    // connecting controller.
    private inner class TransportSessionCallback : MediaSession.Callback {

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
            customButtonHandlers[customCommand.customAction]?.invoke()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
        bridge.setNowPlaying(nowPlaying)
    }

    // Metadata only — no lock, no service, no session-publish state touched.
    // MediaSessionPlugin calls this for a transient or genuinely-empty cursor
    // (see its own comment), which happens on the way to almost every real
    // item too — anything here that reached into service lifecycle turned an
    // ordinary track change into a stop/republish cycle.
    override fun clearNowPlaying() {
        bridge.blank()
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
            servicePromotionRequested = true
            // clear() (a real stop, not a pause) unpublishes this session so
            // the service demotes out of foreground — see its own comment.
            // Republish here so the NEXT play after a stop has a session for
            // the service's onGetSession to answer, exactly like the initial
            // publish in init.
            if (PlaybackForegroundSession.session.value !== session) {
                PlaybackForegroundSession.publish(session)
            }
            startPlaybackService()
        }
        holdLocks(state == TransportPlaybackState.PLAYING)
    }

    override fun setActionHandlers(actions: TransportActions) {
        bridge.setActions(actions)
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

    override fun release() {
        if (released) return
        released = true
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        holdLocks(false)
        // Publish before releasing the Media3 object, so the service's own
        // reconcile sees the `null` transition and removeSession()s a
        // session that still answers, rather than one already torn down
        // underneath it.
        if (PlaybackForegroundSession.session.value === session) {
            PlaybackForegroundSession.publish(null)
        }
        // Guarded rather than gated on the identity check above: that check
        // only tells us whether to touch the published pointer, and a
        // `session.value !== session` reading is also what a perfectly
        // ordinary clear()-then-release() leaves behind — gating the release
        // itself on it would skip a genuine teardown. The double-release this
        // guards against is the OTHER cause of that same reading: the
        // constructor's own pre-emptive release (this class's [init]) running
        // on THIS exact object from a newer instance, ahead of this
        // instance's own (suspend, slower) dispose() — the race [released]'s
        // own doc names, which [released] cannot catch because that
        // pre-emptive call runs on the old instance's raw session directly,
        // never through the old instance's own release(). Not confirmed
        // whether Media3 throws on a second release(); this is a no-op
        // either way if it does not.
        runCatching { session.release() }
    }

    // `startForegroundService`, not `startService` — a background-started
    // service is killed within seconds unless it promotes itself to
    // foreground almost immediately, which is exactly what
    // [NoMercyPlaybackService.reconcile] does the moment it sees this
    // session over `addSession`. This project's minSdk (29) is already past
    // the API 26 floor `startForegroundService` needs, so there is no older
    // path to fall back to.
    private fun startPlaybackService() {
        appContext.startForegroundService(Intent(appContext, NoMercyPlaybackService::class.java))
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
    }
}
