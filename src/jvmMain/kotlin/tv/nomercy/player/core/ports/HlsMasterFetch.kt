// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.net.HttpURLConnection
import java.net.URI

// Reading one small text file, so the desktop can see the ladder libVLC will not
// describe.
//
// A port rather than a client for the reason Fetcher is: an HTTP library in core
// is an HTTP library in every consumer. This is not that — it is the JDK's own
// connection, no dependency added, and it exists because the alternative is
// playing HDR on an SDR screen. libvlc_video_track_t exposes no colour
// primaries and no transfer function in any VLC 3 release, so the master
// playlist's VIDEO-RANGE attribute is the only place the answer lives.
//
// Injectable because a test must not reach the network to prove what the parser
// does with what comes back.
internal fun interface HlsMasterFetch {

    // The manifest text, or null when it could not be read for any reason. Null
    // rather than a throw: a manifest that cannot be fetched must degrade to
    // playing the item exactly as it would have before, not to failing the load.
    fun get(url: String, headers: Map<String, String>): String?
}

// What a manifest read over HTTP costs at most.
//
// Short, because this happens on the way to the first frame and a viewer is
// watching a spinner while it runs. A slow answer is worse than no answer: no
// answer plays the item unnarrowed, which is what happens today anyway.
private const val TIMEOUT_MS: Int = 4_000

// A master playlist is a few hundred bytes to a few kilobytes. Anything past
// this is not one, and reading it into a String would be a media file in memory
// — which is what a mistyped URL or a server that serves a segment for a
// playlist request would produce.
private const val MAX_BYTES: Int = 512 * 1024

// The JDK's own client, which is already on every desktop this runs on.
internal object JdkHlsMasterFetch : HlsMasterFetch {

    // runCatching rather than a list of catch clauses, because the list is what
    // gets one short: an unreadable connection throws IOException, and a URL the
    // caller typed makes URI.create and toURL throw unchecked instead. Naming the
    // shapes of failure individually means missing one, and the one missed reaches
    // a user as a load that never started.
    override fun get(url: String, headers: Map<String, String>): String? =
        runCatching { read(open(url, headers)) }.getOrNull()

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection: HttpURLConnection =
            URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        headers.forEach { (name: String, value: String) ->
            connection.setRequestProperty(name, value)
        }
        return connection
    }

    private fun read(connection: HttpURLConnection): String? {
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body: ByteArray = connection.inputStream.use { it.readNBytes(MAX_BYTES) }
            return body.decodeToString()
        } finally {
            connection.disconnect()
        }
    }
}
