// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer

/**
 * One libVLC player, and the four surfaces anything drives it through.
 *
 * Public because video has to be drawn somewhere and only the application knows
 * where: the UI layer attaches its own frame sink through [videoFrameSink]. The
 * rest of the surface is this module's business — every playback call above goes
 * through the backend's MediaBackend contract, not through here.
 */
public class VlcMediaPlayer internal constructor(
    private val instance: VlcInstance,
    internal val handle: Pointer,
) {

    internal constructor(instance: VlcInstance) : this(instance, instance.newPlayer())

    internal val playback: VlcPlayback = VlcPlayback(instance.binding, handle)

    internal val audio: VlcAudio = VlcAudio(instance.binding, handle)

    internal val tracks: VlcTracks = VlcTracks(instance.binding, handle)

    private var events: VlcEventSpine? = null

    private var video: VlcVideoOutput? = null

    private var released: Boolean = false

    /**
     * Where this player's frames go, set once and before anything plays.
     *
     * libVLC chooses a video output the first time it is told to play, so a sink
     * attached afterwards takes effect on the next item rather than this one —
     * and swapping a live callback surface segfaults it. One sink, set once.
     */
    public fun videoFrameSink(sink: VlcVideoFrameSink) {
        video = VlcVideoOutput(instance.binding, handle, sink)
    }

    internal fun events(listener: VlcPlayerEvents) {
        events = VlcEventSpine(instance.binding, handle, listener)
    }

    // Loading without starting, because the two are separate decisions above:
    // an engine that started on its own would ignore a refused beforePlay.
    //
    // The options travel WITH the item. libVLC reads them while it builds the
    // demuxer chain, so a ladder constraint applied after this is applied too
    // late to decide which rung it opens.
    // The last item's picture, forgotten before this one opens. Without it the
    // canvas keeps the previous film until the new one decodes a frame, and an
    // item that decodes none never replaces it.
    internal fun clearPicture() {
        video?.clearPicture()
    }

    internal fun prepare(mrl: String, options: List<String>) {
        val media: Pointer = open(mrl) ?: return
        options.forEach { option -> instance.binding.media.mediaAddOption(media, option) }
        instance.binding.player.mediaPlayerSetMedia(handle, media)
        // set_media retains it, so this drops the reference this method made
        // rather than the item.
        instance.binding.media.mediaRelease(media)
    }

    private fun open(mrl: String): Pointer? {
        val encoded: String = VlcMediaLocator.encode(mrl)
        return if (VlcMediaLocator.isLocation(encoded)) {
            instance.binding.media.mediaNewLocation(instance.handle, encoded)
        } else {
            instance.binding.media.mediaNewPath(instance.handle, encoded)
        }
    }

    /**
     * Native resources, in the one order that is safe: listeners first so libVLC
     * cannot call into a released player, then the player itself.
     *
     * Not optional. A player dropped without this leaks a decoder thread per
     * item played.
     */
    public fun release() {
        if (released) return
        released = true
        events?.release()
        events = null
        video = null
        instance.binding.player.mediaPlayerRelease(handle)
    }
}
