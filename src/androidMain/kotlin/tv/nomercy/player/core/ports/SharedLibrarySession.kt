// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.nomercy.player.core.plugin.BrowseNode
import tv.nomercy.player.core.plugin.BrowseTreeProvider

// The custom buttons of the playback that is attached to the shared session.
@UnstableApi
internal interface TransportControls {
    val commandIds: Set<String>

    fun press(id: String)
}

// The one MediaLibrarySession of the process. A car follows the session it was
// first answered with, so browsing and playback share it and only its player
// changes (see [SwappableSession]).
@UnstableApi
internal object SharedLibrarySession {
    private const val SESSION_ID = "nomercy-player"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var browseDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var browseScope: CoroutineScope? = null
    private var artworkLoader: ArtworkLoaderExecutor? = null
    private var controls: TransportControls? = null

    private val slot = SwappableSession<MediaLibrarySession, TransportSimpleBasePlayer>(
        newIdle = { TransportSimpleBasePlayer() },
        build = ::buildSession,
        setPlayer = { session, player -> onMain { session.setPlayer(player) } },
        dispose = ::disposeSession,
    )

    fun obtain(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.Default): MediaLibrarySession {
        appContext = context.applicationContext
        browseDispatcher = dispatcher
        return slot.obtain()
    }

    fun attach(
        context: Context,
        player: TransportSimpleBasePlayer,
        playbackControls: TransportControls,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): MediaLibrarySession {
        appContext = context.applicationContext
        browseDispatcher = dispatcher
        controls = playbackControls
        return slot.attach(player)
    }

    // The custom layout belongs to the playback that set it, so it goes with it.
    fun detach(player: TransportSimpleBasePlayer) {
        if (!slot.detach(player)) return
        controls = null
        runCatching { slot.obtain().setCustomLayout(emptyList()) }
    }

    fun isAttached(player: TransportSimpleBasePlayer): Boolean = slot.isAttached(player)

    fun releaseIfIdle() {
        slot.releaseIfIdle()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun buildSession(player: TransportSimpleBasePlayer): MediaLibrarySession {
        val context: Context = checkNotNull(appContext) { "obtain() sets the context first" }
        val loader = ArtworkLoaderExecutor().also { artworkLoader = it }
        val scope = CoroutineScope(browseDispatcher + SupervisorJob()).also { browseScope = it }
        return MediaLibrarySession.Builder(context, player, SharedSessionCallback(scope))
            .setId(SESSION_ID)
            .setBitmapLoader(DataSourceBitmapLoader(loader.service, artworkDataSourceFactory(context)))
            .build()
    }

    private fun disposeSession(session: MediaLibrarySession) {
        browseScope?.cancel()
        browseScope = null
        artworkLoader?.release()
        artworkLoader = null
        session.release()
    }

    // Media3's default loader sends no Authorization header, so a car or the
    // lock screen would get the title but never the cover (ArtworkAuthHeaders).
    private fun artworkDataSourceFactory(context: Context): DataSource.Factory {
        val headers: () -> Map<String, String> = { PlatformEnvironment.artworkAuthHeaders?.invoke().orEmpty() }
        val client = SharedHttpClient.derive {
            addInterceptor { chain ->
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
        }
        return DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
    }

    private class SharedSessionCallback(private val scope: CoroutineScope) : MediaLibrarySession.Callback {

        // A car asks on a binder thread and expects an immediate future, so each
        // answer resolves off the main thread; a tree that throws is an error
        // result rather than a future nobody completes.
        private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val pending: SettableFuture<LibraryResult<T>> = SettableFuture.create()
            scope.launch {
                pending.set(
                    runCatching { block() }.getOrElse { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) },
                )
            }
            return pending
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            controls?.commandIds?.forEach { id -> available.add(SessionCommand(id, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.accept(
                available.build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            controls?.press(customCommand.customAction)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = answer {
            LibraryResult.ofItem(PlatformEnvironment.browseTree.root().toMediaItem(), params)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
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

        // The bridge has no queue of its own, so nothing is added to it; the app
        // plays the selection through its own path and the metadata comes back
        // as setNowPlaying.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            mediaItems.firstOrNull()?.mediaId?.let { PlatformEnvironment.browseSelection?.invoke(it) }
            return Futures.immediateFuture(emptyList())
        }
    }
}
