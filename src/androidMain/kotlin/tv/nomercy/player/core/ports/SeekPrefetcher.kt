// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

private const val LOG_TAG = "nm-prefetch"
private const val CACHE_DIR = "nm-seek-prefetch"
private const val CACHE_BYTES = 64L * 1024 * 1024
private const val WINDOW_US = 10_000_000L
private const val MICROS_PER_SECOND = 1_000_000.0

// Media3 reads every segment through [playback], which answers from the cache
// and never writes to it. Only [prefetch] writes, so the disk holds the few
// segments a known seek will need and nothing a viewer merely watched.
@OptIn(UnstableApi::class)
internal class SeekPrefetcher(context: Context, private val upstream: DataSource.Factory) {

    private val cache: SimpleCache = SeekCache.get(context)

    val playback: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val writing: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writers: MutableList<CacheWriter> = CopyOnWriteArrayList()
    private var clearing: Job? = null

    fun prefetch(manifest: HlsManifest, audioFormatId: String?, audioLanguage: String?, seconds: Double) {
        val pending: Job? = clearing
        scope.launch {
            pending?.join()
            val fromUs: Long = (seconds * MICROS_PER_SECOND).toLong()
            val audio: HlsMediaPlaylist? = audioPlaylist(manifest, audioFormatId, audioLanguage)
            listOfNotNull(manifest.mediaPlaylist, audio)
                .flatMap { playlist -> specsNear(playlist, fromUs) }
                .forEach(::write)
        }
    }

    // A new source: what was fetched for the last one is never read again.
    fun forget() {
        writers.forEach(CacheWriter::cancel)
        clearing = scope.launch { cache.keys.toList().forEach(cache::removeResource) }
    }

    fun release() {
        writers.forEach(CacheWriter::cancel)
        scope.cancel()
    }

    private fun audioPlaylist(manifest: HlsManifest, formatId: String?, language: String?): HlsMediaPlaylist? {
        val renditions = manifest.multivariantPlaylist.audios.filter { it.url != null }
        val chosen = renditions.firstOrNull { it.format.id == formatId }
            ?: renditions.firstOrNull { language != null && it.format.language == language }
            ?: return null
        val uri = chosen.url ?: return null
        return runCatching {
            DataSourceInputStream(upstream.createDataSource(), DataSpec(uri)).use { stream ->
                HlsPlaylistParser(manifest.multivariantPlaylist, null).parse(uri, stream) as? HlsMediaPlaylist
            }
        }.onFailure { Log.w(LOG_TAG, "audio playlist unavailable: ${it.message}") }.getOrNull()
    }

    private fun specsNear(playlist: HlsMediaPlaylist, fromUs: Long): List<DataSpec> {
        val spans: List<SegmentSpan> = playlist.segments.map { SegmentSpan(it.relativeStartTimeUs, it.durationUs) }
        return segmentsCovering(spans, fromUs, WINDOW_US)
            .flatMap { index -> listOfNotNull(playlist.segments[index].initializationSegment, playlist.segments[index]) }
            .distinct()
            .map { segment -> specOf(playlist.baseUri, segment) }
    }

    private fun specOf(baseUri: String, segment: HlsMediaPlaylist.Segment): DataSpec {
        val uri = UriUtil.resolveToUri(baseUri, segment.url)
        if (segment.byteRangeLength == C.LENGTH_UNSET.toLong()) return DataSpec(uri)
        return DataSpec(uri, segment.byteRangeOffset, segment.byteRangeLength)
    }

    private fun write(spec: DataSpec) {
        val writer = CacheWriter(writing.createDataSourceForDownloading(), spec, null, null)
        writers += writer
        try {
            writer.cache()
        } catch (failure: IOException) {
            Log.w(LOG_TAG, "prefetch of ${spec.uri.lastPathSegment} stopped: ${failure.message}")
        } finally {
            writers -= writer
        }
    }
}

// One per process: SimpleCache locks its folder, and a second instance on it throws.
@OptIn(UnstableApi::class)
private object SeekCache {
    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.cacheDir, CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }
}
