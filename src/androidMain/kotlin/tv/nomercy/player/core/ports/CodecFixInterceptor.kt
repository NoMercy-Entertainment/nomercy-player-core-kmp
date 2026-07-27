// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.charset.Charset

// Repairs a codec string that some older encodes wrote wrong.
//
// `avc1.F40028` is not a codec string. The middle byte is the H.264 profile and
// High is 0x64, so an F there makes Media3 reject the variant as unplayable —
// the ladder loses a rung, or the whole stream refuses to open, on files that
// decode perfectly once the manifest says what they actually are.
//
// It is a manifest rewrite rather than a decoder change because the file is
// fine and only its description is wrong. Re-encoding a library to fix a typo
// in the metadata would be the alternative.
//
// Only HLS playlists are touched, and only the CODECS attribute inside them. A
// blanket search-and-replace across every response body would eventually find
// those four characters inside a media segment.
// Reports what it repaired rather than logging it, matching the bandwidth
// sanitiser next door. A library that wrote to android.util.Log would be
// choosing the host's log policy for it, and would drag the Android framework
// into a test that is otherwise pure string work.
public class CodecFixInterceptor(
    private val onRepaired: (Int) -> Unit = {},
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response: Response = chain.proceed(chain.request())
        val body: ResponseBody = response.body ?: return response
        if (!isPlaylist(response)) return response

        return response.newBuilder().body(repaired(body)).build()
    }

    // Always a fresh body, even when nothing changed.
    //
    // An OkHttp body can only be read once, and it has to be read to be
    // searched. Handing the original response back after consuming it gives
    // the caller an empty stream, which reads as a server that returned
    // nothing rather than as an interceptor that ate the manifest.
    private fun repaired(body: ResponseBody): ResponseBody {
        val bytes: ByteArray = body.bytes()
        val original: String = bytes.toString(UTF8)
        var count = 0
        val fixed: String = MALFORMED_CODEC.replace(original) { match ->
            count++
            match.groupValues[PREFIX] + HIGH_PROFILE + match.groupValues[SUFFIX]
        }

        if (count == 0) return bytes.toResponseBody(body.contentType())

        onRepaired(count)
        val mediaType: MediaType? = body.contentType() ?: PLAYLIST_MIME.toMediaTypeOrNull()
        return fixed.toByteArray(UTF8).toResponseBody(mediaType)
    }

    private fun isPlaylist(response: Response): Boolean =
        response.header("Content-Type")?.contains(PLAYLIST_MIME, ignoreCase = true) == true

    private companion object {
        const val PLAYLIST_MIME = "application/vnd.apple.mpegurl"
        val UTF8: Charset = Charset.forName("UTF-8")

        // Anchored to the CODECS attribute so nothing outside it is rewritten.
        val MALFORMED_CODEC = Regex("""(CODECS="[^"]*avc1\.)([Ff]4)([0-9A-Fa-f]{4})""")

        const val PREFIX = 1
        const val SUFFIX = 3

        // 0x64 is H.264 High profile, which is what these files actually are.
        const val HIGH_PROFILE = "64"
    }
}
