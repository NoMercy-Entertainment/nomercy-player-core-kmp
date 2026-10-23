// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
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

// The session a car gets when it opens NoMercy before anything plays.
//
// The id differs from the transport's on purpose: a stopped transport keeps its
// session registered (unpublished) for the next play, so reusing its id here
// would throw "Session ID must be unique". The coordinator still releases this
// one before the transport's goes in.
@UnstableApi
internal class BrowseOnlySession(
    context: Context,
    browseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val scope = CoroutineScope(browseDispatcher + SupervisorJob())

    val session: MediaLibrarySession =
        MediaLibrarySession.Builder(context, TransportSimpleBasePlayer(), BrowseCallback())
            .setId(BROWSE_SESSION_ID)
            .build()

    fun release() {
        scope.cancel()
        session.release()
    }

    private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val pending: SettableFuture<LibraryResult<T>> = SettableFuture.create()
        scope.launch {
            pending.set(runCatching { block() }.getOrElse { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) })
        }
        return pending
    }

    private inner class BrowseCallback : MediaLibrarySession.Callback {

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
            val all = PlatformEnvironment.browseTree.children(parentId)
            LibraryResult.ofItemList(all.browsePage(page, pageSize).map { it.toMediaItem() }, params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            mediaItems.firstOrNull()?.mediaId?.let { PlatformEnvironment.browseSelection?.invoke(it) }
            return Futures.immediateFuture(emptyList())
        }
    }

    private companion object {
        const val BROWSE_SESSION_ID = "nomercy-browse"
    }
}
