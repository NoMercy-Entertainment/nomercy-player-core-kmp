// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What a stream failed with.
 *
 * The decoder's own error and the downloader's are kept apart because they
 * answer different questions, and a viewer stuck on a black screen needs
 * whichever of the two actually happened: "this device cannot decode this" is a
 * different message, and a different fix, from "the segment never arrived".
 */
public sealed interface StreamErrorPayload {

    /** The decoder's own code, as the media element reports it. */
    public data class Media(val code: Int, val message: String? = null) : StreamErrorPayload

    /** The stream layer's report: a fetch, a manifest, a key exchange. */
    public data class Stream(
        val kind: String,
        val fatal: Boolean,
        val message: String? = null,
        val url: String? = null,
    ) : StreamErrorPayload
}
