// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.session

/**
 * What the lock screen, the notification and the car head unit show.
 *
 * Every field is optional and every field is separate: a title with the artist
 * appended is one string the OS cannot lay out, cannot marquee independently
 * and cannot hand to a voice assistant as "who is this". The web keeps them
 * apart for the same reason and so does every native session API.
 */
// The name is the WEB'S, and the type conformance ratchet requires it exactly.
// detekt forbids "Data" in a class name and is right in general; here the
// alternative is a port whose type a consumer cannot find by the name the
// documentation gives it.
@Suppress("ForbiddenClassName")
public data class MediaSessionMetadata(
    /** Track or episode title. */
    val title: String? = null,

    /** Artist, or the creator. */
    val artist: String? = null,

    /** Album, or the series. */
    val album: String? = null,

    /**
     * Artwork, several sizes.
     *
     * A list rather than one image because the consumer of it picks: a lock
     * screen, a notification and a car display want three different sizes, and
     * sending one forces the OS to rescale for two of them.
     */
    val artwork: List<MediaImage> = emptyList(),
)

/**
 * One artwork image.
 *
 * [sizes] and [type] are hints the OS uses to choose, in the web's own spelling
 * — `"512x512"`, `"image/png"` — because this metadata crosses to a receiver
 * that reads the web shape.
 */
public data class MediaImage(
    val src: String,
    val sizes: String? = null,
    val type: String? = null,
)

/**
 * How the media-session plugin behaves.
 *
 * Empty, exactly as the web declares it. Kept rather than dropped because the
 * plugin's option type is part of the surface a consumer writes against, and a
 * plugin that took no options at all would be the one plugin whose signature
 * differs — which is a difference a consumer discovers at the call site.
 */
public class MediaSessionOptions
