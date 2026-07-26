// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.Interceptor
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
public class BandwidthSanitizingInterceptor(
    private val ceiling: Long,
    private val floor: Long = BandwidthSanitizer.DEFAULT_FLOOR,
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
        val result = BandwidthSanitizer.sanitize(original, ceiling, floor)
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
