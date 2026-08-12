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
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

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
        PlaybackForegroundSession.session.value?.release()
        MediaSession.Builder(appContext, bridge)
            .setId(SESSION_ID)
            .build()
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
        if (state == TransportPlaybackState.PLAYING && !servicePromotionRequested) {
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
        bridge.setPlayback(state, positionMs)
        holdLocks(state == TransportPlaybackState.PLAYING)
    }

    override fun setActionHandlers(actions: TransportActions) {
        bridge.setActions(actions)
    }

    // A real stop, not a pause — the viewer closed the player rather than
    // merely paused it. Blanking the bridge alone left the foreground
    // service (and its notification) pinned showing whatever it last had,
    // because nothing here ever told NoMercyPlaybackService.reconcile() that
    // playback had genuinely ended (confirmed live: stop left a stale
    // notification behind, real device, 2026-08-12). Unpublishing does that
    // — reconcile() sees null and calls stopSelf(), which removes the
    // notification. servicePromotionRequested resets so the NEXT real play
    // re-promotes to foreground instead of silently no-op'ing forever.
    override fun clear() {
        bridge.blank()
        holdLocks(false)
        if (PlaybackForegroundSession.session.value === session) {
            PlaybackForegroundSession.publish(null)
        }
        servicePromotionRequested = false
    }

    override fun release() {
        if (released) return
        released = true
        holdLocks(false)
        // Publish before releasing the Media3 object, so the service's own
        // reconcile sees the `null` transition and removeSession()s a
        // session that still answers, rather than one already torn down
        // underneath it.
        if (PlaybackForegroundSession.session.value === session) {
            PlaybackForegroundSession.publish(null)
        }
        session.release()
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
    }
}
