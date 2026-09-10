// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// What keeps the process alive with the screen off (P21.20).
//
// [Media3SystemTransport] builds its `MediaLibrarySession` on its own, from
// whatever `Context` the app handed the library — never from a running
// `Service`, because the library has no `Service` to build one from until a
// player actually exists. That is the wrong order for `MediaLibraryService`,
// which wants to own the session it manages a notification for. This bridges
// the two ownership models instead of forcing one onto the other:
// [PlaybackForegroundSession] is where the transport PUBLISHES the session it
// already built, and this service is the framework-visible thing that reads
// what was published and does the two jobs `MediaLibraryService` exists for
// — answer the system's `onGetSession` query, and register the session with
// its own base class's notification manager so the base class can promote
// itself to foreground the moment something plays.
//
// A `MediaLibraryService` rather than a plain `MediaSessionService` because
// this is also the thing a Bluetooth head unit enumerates when it lists the
// players it can browse. That enumeration is what makes a car show NoMercy at
// all, and on Android 13+ it is also what makes the car fetch cover art.
//
// Registered in this library's own `AndroidManifest.xml` (merged into the
// consumer's) rather than left for an app to declare, the same way the
// permissions in that manifest already are — a consumer that adopts the
// player gets the whole subsystem, not a checklist of things it also has to
// wire by hand.
@UnstableApi
public class NoMercyPlaybackService : MediaLibraryService() {

    private var scopeJob: Job? = null
    private var attached: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        // The consuming app's own icon and channel name, not this library's —
        // see MediaNotificationBranding's own comment. Media3's own default
        // provider (stock icon, generic channel name) is what a consumer
        // that supplies nothing keeps getting. Read through
        // PlatformEnvironment, same seam the Android Context itself is
        // installed and read through — not a second static holder.
        PlatformEnvironment.mediaNotificationBranding?.let { branding ->
            setMediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(this)
                    .setChannelId(branding.channelId)
                    .setChannelName(branding.channelNameResId)
                    .build()
                    .also { it.setSmallIcon(branding.smallIconResId) },
            )
        }
        // Unconditional — this project's minSdk (29) is already past the O
        // (26) floor a notification channel needs, same as
        // [Media3SystemTransport.startPlaybackService]'s own note.
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(STARTUP_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(STARTUP_CHANNEL_ID, STARTUP_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val job = Job()
        scopeJob = job
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        PlaybackForegroundSession.session
            .onEach { session -> reconcile(session) }
            .launchIn(scope)
    }

    // The promotion that used to never come.
    //
    // `startForegroundService` starts a clock owned by the PLATFORM, not this
    // process: Android kills the app with ForegroundServiceDidNotStartInTimeException
    // unless SOMETHING calls `Service.startForeground` within a few seconds,
    // full stop — it does not wait for a real reason to show up first. The
    // base class calls it for us, but only once a registered session reports
    // that it is actually PLAYING, and that depends on the source finishing
    // its buffering — a network condition the platform's clock never asked
    // about. `stopIfNeverPromoted` below only covers the "no play is coming"
    // half of that gap (a play that failed, a source that never opened); a
    // play that IS coming but is simply slow to buffer correctly declines to
    // self-stop and then gets killed anyway once Android's real deadline
    // passes, because nothing had satisfied it in the meantime. Confirmed
    // live, real TV, 2026-09-10: a device-transfer claim (ChangeDeviceCommand
    // then StartPlaybackCommand landing here as a genuine local play) crashed
    // the app with ForegroundServiceDidNotStartInTimeException while
    // playWhenReady was already true — the exact case the old comment on this
    // method assumed the base class would always eventually cover.
    //
    // So a backstop is armed here instead: [promoteIfMedia3HasNot] promotes
    // with a placeholder, but only once Media3 has demonstrably not done it
    // first, and only for as long as it takes Media3 to catch up.
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        mainHandler.removeCallbacks(promoteIfMedia3HasNot)
        mainHandler.postDelayed(promoteIfMedia3HasNot, BACKSTOP_PROMOTION_MS)
        mainHandler.removeCallbacks(stopIfNeverPromoted)
        mainHandler.postDelayed(stopIfNeverPromoted, PROMOTION_GRACE_MS)
        return super.onStartCommand(intent, flags, startId)
    }

    // Only ever runs when Media3 has NOT promoted in time on its own.
    //
    // Measured on a real device: in the ordinary case Media3 posts its own
    // rich media notification (transport controls, artwork, id
    // DEFAULT_NOTIFICATION_ID) within milliseconds of `reconcile`'s
    // `addSession`, and promoting with a placeholder before that REPLACES it
    // — the shade kept the blank placeholder and lost the controls, because
    // Media3 does not re-post a notification whose state it believes is
    // already current. So this asks whether Media3 got there first and stays
    // out of the way when it did.
    private val promoteIfMedia3HasNot: Runnable = Runnable {
        if (media3HasPosted()) return@Runnable
        runCatching { startForeground(STARTUP_NOTIFICATION_ID, startupNotification()) }
        // Media3 promotes onto its own id once the source finally opens; this
        // placeholder is then just a second notification nobody asked for.
        mainHandler.postDelayed(retireePlaceholder, HANDOVER_POLL_MS)
    }

    private val retireePlaceholder: Runnable = object : Runnable {
        override fun run() {
            if (media3HasPosted()) {
                runCatching {
                    getSystemService(NotificationManager::class.java).cancel(STARTUP_NOTIFICATION_ID)
                }
                return
            }
            if (attached != null) mainHandler.postDelayed(this, HANDOVER_POLL_MS)
        }
    }

    private fun media3HasPosted(): Boolean =
        runCatching {
            getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any { it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID }
        }.getOrDefault(false)

    // Deliberately minimal — branding may not be installed, and this is
    // visible for at most the few hundred ms until `reconcile` hands the base
    // class a real session to build its own notification from. The system
    // fallback icon costs zero dependency on the consuming app having
    // supplied anything.
    private fun startupNotification(): Notification {
        val branding = PlatformEnvironment.mediaNotificationBranding
        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) STARTUP_CHANNEL_ID else ""
        return Notification.Builder(this, channelId)
            .setSmallIcon(branding?.smallIconResId ?: android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private val mainHandler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val stopIfNeverPromoted: Runnable = Runnable {
        // playWhenReady rather than isPlaying: a stream still opening has not
        // started yet and is exactly the case worth waiting for.
        val wanted: Boolean = runCatching {
            attached?.player?.let { it.isPlaying || it.playWhenReady } == true
        }.getOrDefault(false)
        if (!wanted) stopWithoutLeavingAPromotionPending()
    }

    // Stopping is only a safe answer to the platform's clock once something
    // has actually called `startForeground` for the start command that
    // started it. Measured on a real device: a service started with
    // `startForegroundService` and stopped ~500ms later, before promoting,
    // still died with ForegroundServiceDidNotStartInTimeException
    // ("Bringing down service while still waiting for start foreground") —
    // the stop did not cancel the clock, it just removed anything that could
    // have answered it. So every stop path promotes first when nothing else
    // has, and the promotion it posts is torn down by the stop that follows
    // it a moment later.
    private fun stopWithoutLeavingAPromotionPending() {
        if (!media3HasPosted()) {
            runCatching { startForeground(STARTUP_NOTIFICATION_ID, startupNotification()) }
        }
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = attached

    // A `null` published session — playback stopped and
    // [Media3SystemTransport.release] ran — is `MediaLibraryService`'s own
    // documented signal to stop itself; `stopSelf()` is what lets the
    // foreground promotion (and the notification with it) actually end
    // rather than lingering as an empty session with a stale "Nothing
    // playing" notification nobody asked to keep seeing.
    private fun reconcile(session: MediaLibrarySession?) {
        // Guarded: removeSession() throws "session not found" if the old
        // session was already torn down by some other path (its own
        // release(), a prior reconcile that raced this one) — confirmed
        // live, real device, 2026-08-15. Left unguarded this was fatal on
        // Dispatchers.Main.immediate with no CoroutineExceptionHandler,
        // which also permanently killed this StateFlow collector — every
        // later publish() went unreconciled, producing the next crash
        // downstream ("Session ID must be unique") on the next transition.
        attached?.let { old -> if (old !== session) runCatching { removeSession(old) } }
        attached = session
        if (session != null) {
            // Guarded for the same reason the removal is: a session id that is
            // already registered answers with a throw, and this runs on a
            // collector whose death silently strands every later transition.
            runCatching { addSession(session) }
        } else {
            mainHandler.removeCallbacks(stopIfNeverPromoted)
            mainHandler.removeCallbacks(promoteIfMedia3HasNot)
            stopWithoutLeavingAPromotionPending()
        }
    }

    private companion object {
        // Under the platform's own window, which is five seconds on the devices
        // this runs on. Long enough for a real play to promote, short enough
        // that a play which never happens ends quietly instead of fatally.
        const val PROMOTION_GRACE_MS: Long = 4_000L

        // Under both the platform's own deadline and [PROMOTION_GRACE_MS], so
        // an ordinary play has already promoted through Media3 by the time
        // this runs and a slow one still gets a foreground state before
        // anything kills the process for not having one.
        const val BACKSTOP_PROMOTION_MS: Long = 2_500L

        // Only alive while the placeholder is, waiting for the real
        // notification to arrive so the placeholder can be withdrawn.
        const val HANDOVER_POLL_MS: Long = 1_000L

        const val STARTUP_CHANNEL_ID = "nomercy-playback-startup"
        const val STARTUP_NOTIFICATION_ID = 8271
    }

    override fun onDestroy() {
        // Same guard as reconcile's, for the same reason: removeSession throws
        // "session not found" when the session was already torn down by another
        // path, and a throw here is fatal — the phone restarted on a play press
        // (measured 2026-08-17, IllegalArgumentException from this class).
        mainHandler.removeCallbacks(stopIfNeverPromoted)
        mainHandler.removeCallbacks(promoteIfMedia3HasNot)
        mainHandler.removeCallbacks(retireePlaceholder)
        attached?.let { runCatching { removeSession(it) } }
        attached = null
        scopeJob?.cancel()
        scopeJob = null
        super.onDestroy()
    }
}

// The publish side of the bridge [NoMercyPlaybackService] reads from.
//
// [Media3SystemTransport] calls [publish] when its session is built and
// again with `null` in [Media3SystemTransport.release] — a `StateFlow`
// rather than a one-shot call because the service may not exist yet the
// first time a session is published (its own process start is what
// [Media3SystemTransport] triggers, immediately after) and needs the current
// value the moment it does.
public object PlaybackForegroundSession {
    private val mutable = MutableStateFlow<MediaLibrarySession?>(null)
    public val session: StateFlow<MediaLibrarySession?> = mutable.asStateFlow()

    public fun publish(session: MediaLibrarySession?) {
        mutable.value = session
    }
}
