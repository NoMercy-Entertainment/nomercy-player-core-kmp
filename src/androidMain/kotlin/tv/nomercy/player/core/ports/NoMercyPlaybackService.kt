// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
// [Media3SystemTransport] builds its `MediaSession` on its own, from
// whatever `Context` the app handed the library — never from a running
// `Service`, because the library has no `Service` to build one from until a
// player actually exists. That is the wrong order for `MediaSessionService`,
// which wants to own the session it manages a notification for. This bridges
// the two ownership models instead of forcing one onto the other:
// [PlaybackForegroundSession] is where the transport PUBLISHES the session it
// already built, and this service is the framework-visible thing that reads
// what was published and does the two jobs `MediaSessionService` exists for
// — answer the system's `onGetSession` query, and register the session with
// its own base class's notification manager so the base class can promote
// itself to foreground the moment something plays.
//
// Registered in this library's own `AndroidManifest.xml` (merged into the
// consumer's) rather than left for an app to declare, the same way the
// permissions in that manifest already are — a consumer that adopts the
// player gets the whole subsystem, not a checklist of things it also has to
// wire by hand.
@UnstableApi
public class NoMercyPlaybackService : MediaSessionService() {

    private var scopeJob: Job? = null
    private var attached: MediaSession? = null

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
        val job = Job()
        scopeJob = job
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        PlaybackForegroundSession.session
            .onEach { session -> reconcile(session) }
            .launchIn(scope)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = attached

    // A `null` published session — playback stopped and
    // [Media3SystemTransport.release] ran — is `MediaSessionService`'s own
    // documented signal to stop itself; `stopSelf()` is what lets the
    // foreground promotion (and the notification with it) actually end
    // rather than lingering as an empty session with a stale "Nothing
    // playing" notification nobody asked to keep seeing.
    private fun reconcile(session: MediaSession?) {
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
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Same guard as reconcile's, for the same reason: removeSession throws
        // "session not found" when the session was already torn down by another
        // path, and a throw here is fatal — the phone restarted on a play press
        // (measured 2026-08-17, IllegalArgumentException from this class).
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
    private val mutable = MutableStateFlow<MediaSession?>(null)
    public val session: StateFlow<MediaSession?> = mutable.asStateFlow()

    public fun publish(session: MediaSession?) {
        mutable.value = session
    }
}
