// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One HTTP request, as much of it as a plugin is allowed to decide. The token,
// the refresh-and-retry and the base URL are the host's business, which is why
// there is no auth header here for a plugin to get wrong.
public data class FetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
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
