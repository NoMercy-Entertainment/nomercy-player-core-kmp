// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * The player's own HTTP requests.
 *
 * Suspending rather than callback-based, because every caller is already inside
 * a coroutine and a callback would make each one build its own bridge back —
 * several bridges, several ways of handling a cancellation.
 *
 * This is what the PLAYER fetches: manifests it parses itself, subtitles,
 * artwork, translations. Media segments never come through here on a native
 * engine — the engine downloads those itself, which is what AuthHeaderProvider
 * exists for.
 */
public fun interface Fetch {
    public suspend fun fetch(url: String, options: FetchOptions): FetchResponse
}

// One HTTP request, as much of it as a plugin is allowed to decide. The token,
// the refresh-and-retry and the base URL are the host's business, which is why
// there is no auth header here for a plugin to get wrong.
public data class FetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    // The request's binary half, which was missing while the RESPONSE has had
    // both since it was written. A DRM licence exchange POSTs a binary
    // challenge, so with a text-only body it could not be expressed at all —
    // and the plugin that needed it simply had no fetchLicense.
    //
    // Both, rather than one replacing the other: a plugin posting JSON wants
    // the string and one posting a challenge wants the bytes, and forcing
    // either through the other means an encoding step that corrupts the case
    // it was not written for.
    val bodyBytes: ByteArray? = null,
)

// [body] is the response as text and [bytes] as it arrived. Both, because a
// plugin fetching a subtitle wants the first and one fetching the font that
// subtitle names wants the second, and a transport that only offered text would
// send every font through a base64 detour.
//
// bytes is null when the host did not keep them, which is the common case for a
// text response.
public class FetchResponse(
    public val status: Int,
    public val headers: Map<String, String> = emptyMap(),
    public val body: String = "",
    public val bytes: ByteArray? = null,
) {
    // Not a data class: the generated equals would compare bytes by identity,
    // so two identical responses would be unequal and any test asserting on one
    // would be asserting nothing.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FetchResponse) return false
        return status == other.status &&
            headers == other.headers &&
            body == other.body &&
            bytesEqual(bytes, other.bytes)
    }

    override fun hashCode(): Int {
        var result: Int = status
        result = HASH_FACTOR * result + headers.hashCode()
        result = HASH_FACTOR * result + body.hashCode()
        result = HASH_FACTOR * result + (bytes?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "FetchResponse(status=$status, headers=$headers, body=$body, bytes=${bytes?.size ?: 0} bytes)"

    private companion object {
        const val HASH_FACTOR = 31

        fun bytesEqual(left: ByteArray?, right: ByteArray?): Boolean = when {
            left == null && right == null -> true
            left == null || right == null -> false
            else -> left.contentEquals(right)
        }
    }
}
