// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * What the receiver is told to play.
 *
 * [extras] carries the fields a particular receiver understands and this library
 * does not name. The web's type extends `Record<string, unknown>` for the same
 * reason: a custom receiver is the normal case here, and a closed shape would
 * make every one of them need a change to this file.
 */
// The name is the WEB'S and the type conformance ratchet requires it exactly.
// detekt forbids "Info" in a class name and is right in general; here the
// alternative is a type a consumer cannot find by the name the documentation
// gives it, on the one surface that also has to line up with a receiver's SDK.
@Suppress("ForbiddenClassName")
public data class CastMediaInfo(
    val contentId: String,
    val contentType: String,
    val metadata: CastMediaMetadata? = null,
    val streamType: CastStreamType? = null,
    val extras: Map<String, Any?> = emptyMap(),
)

/** What the receiver shows about it. */
@Suppress("ForbiddenClassName")
public data class CastMediaMetadata(
    val title: String? = null,
    val extras: Map<String, Any?> = emptyMap(),
)

/** Whether the receiver treats the stream as a file or a broadcast. */
public enum class CastStreamType(public val id: String) {
    BUFFERED("BUFFERED"),
    LIVE("LIVE"),
}
