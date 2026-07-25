// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.PlaylistItem

// Everything a preload decision is allowed to depend on.
public data class PreloadContext(
    val currentTime: Double,
    val duration: Double,
    val nextItem: PlaylistItem?,
)

// One thing to fetch before the next item starts. [category] routes it through
// the host's auth-aware fetch, so a preloaded manifest carries the same token a
// played one would.
public data class PreloadAsset(
    val url: String,
    val category: String,
    val mode: String = "metadata",
)

// When and what to fetch ahead of the next item, so a transition does not start
// with a spinner.
public interface PreloadStrategy {
    public fun shouldPreload(context: PreloadContext): Boolean
    public fun assetsToPreload(item: PlaylistItem): List<PreloadAsset>
    public fun cancel()
}

// Fires once the playhead is within [leadSeconds] of the end.
//
// Returns no assets: what is worth prefetching differs between a video
// manifest and an album cover, so each library composes its own list rather
// than core guessing.
public class DefaultPreloadStrategy(private val leadSeconds: Double = 10.0) : PreloadStrategy {
    override fun shouldPreload(context: PreloadContext): Boolean {
        // Nothing to preload, and an unknown duration means there is no end to
        // measure from — a live stream, or metadata that has not arrived.
        if (context.nextItem == null || context.duration <= 0.0) return false
        return context.currentTime >= context.duration - leadSeconds
    }

    override fun assetsToPreload(item: PlaylistItem): List<PreloadAsset> = emptyList()

    override fun cancel(): Unit = Unit
}
