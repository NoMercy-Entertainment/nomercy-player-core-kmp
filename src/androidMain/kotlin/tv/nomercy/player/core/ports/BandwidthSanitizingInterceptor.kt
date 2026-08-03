// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.Interceptor
import tv.nomercy.player.core.media.QualityDescriptor
import tv.nomercy.player.core.stream.MasterPlaylistRewriter
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"

// The bandwidth rule, carried into Media3's data source.
//
// Thin on purpose. Everything worth being sure about lives in
// BandwidthSanitizer, which takes text and returns text; this is the dozen lines
// that get a manifest out of a Response and a rewritten one back in.
//
// Only playlists are touched. Reading a segment's bytes into memory to search
// them for an attribute they cannot contain would turn every video into a
// buffer the size of the file.
// [keep] narrows the ladder before the engine ever sees it. Null leaves every
// variant in place.
//
// Dropping a rung here rather than refusing it later is the difference between a
// player that never offers a stream it cannot decode and one that offers it,
// starts it, and fails. The engine's own adaptation cannot climb into a variant
// that is not in the manifest it was given.
public class BandwidthSanitizingInterceptor(
    private val ceiling: Long,
    private val floor: Long = BandwidthSanitizer.DEFAULT_FLOOR,
    private val keep: Collection<QualityDescriptor>? = null,
    /**
     * What the master playlist DECLARED, before anything narrowed it.
     *
     * This is the only place a Media3 engine can learn a variant's dynamic range.
     * `Format.colorInfo` is null for an HLS variant until its decoder has been
     * configured — so reading the range off the format answers SDR for every
     * rung of a ladder whose manifest says `VIDEO-RANGE=PQ`, and an HDR film was
     * reported as SDR by a player holding the playlist that said otherwise.
     */
    private val onVariants: (List<QualityDescriptor>) -> Unit = {},
    private val onAdjusted: (BandwidthSanitizer.Adjustment) -> Unit = {},
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response: Response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (!isPlaylist(response)) return response

        // Read once. The body is a one-shot stream, so returning the original
        // response after consuming it hands the player an empty manifest — which
        // is the failure mode of every interceptor that forgets.
        val original: String = body.string()

        // Narrowed first, sanitized second. Sanitizing a variant that is about
        // to be dropped is work for nobody, and reporting an adjustment to a
        // rung the engine will never see would make the adjustment log lie about
        // what happened.
        // Before the narrowing, because the declaration is about the stream and
        // not about what this device was willing to keep.
        MasterPlaylistRewriter.variants(original).takeIf { it.isNotEmpty() }?.let(onVariants)

        val narrowed: String = keep?.let { MasterPlaylistRewriter.rewrite(original, it) } ?: original
        val result = BandwidthSanitizer.sanitize(narrowed, ceiling, floor)
        result.adjustments.forEach(onAdjusted)

        return response.newBuilder()
            .body(result.playlist.toResponseBody(body.contentType()))
            .build()
    }

    private fun isPlaylist(response: Response): Boolean {
        val declared: String = response.header("Content-Type").orEmpty()
        if (declared.contains(HLS_CONTENT_TYPE, ignoreCase = true)) return true
        // Servers that serve .m3u8 as text/plain are common enough that trusting
        // the header alone means the sanitizer silently never runs.
        return response.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true)
    }
}
