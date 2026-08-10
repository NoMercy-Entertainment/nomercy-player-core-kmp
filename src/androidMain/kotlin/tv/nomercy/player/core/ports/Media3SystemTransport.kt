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

    private val session: MediaSession =
        MediaSession.Builder(appContext, bridge).setId(SESSION_ID).build()

    init {
        // Published before the service is asked to start, not after — a
        // service whose first `onGetSession` call raced this init's own
        // assignment would answer null to the very query it started for.
        PlaybackForegroundSession.publish(session)
        startPlaybackService()
    }

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

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        bridge.setPlayback(state, positionMs)
        holdLocks(state == TransportPlaybackState.PLAYING)
    }

    override fun setActionHandlers(actions: TransportActions) {
        bridge.setActions(actions)
    }

    override fun clear() {
        bridge.blank()
        holdLocks(false)
    }

    override fun release() {
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
