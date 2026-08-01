// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

// What the current item contains, and which of it is playing.
//
// Empty until the demuxer has read the container, which is after playback
// starts rather than after the item is set. Asking earlier is not an error and
// does not become one; it answers nothing, the same way it always did.
internal class VlcTracks(private val binding: LibVlcBinding, private val handle: Pointer) {

    fun all(): List<VlcTrack> {
        val media: Pointer = binding.player.mediaPlayerGetMedia(handle) ?: return emptyList()
        return try {
            read(media)
        } finally {
            // get_media retains, so this balances the read rather than closing
            // the item. The player keeps its own reference.
            binding.media.mediaRelease(media)
        }
    }

    fun video(): Int = binding.video.videoGetTrack(handle)

    fun video(id: Int) {
        binding.video.videoSetTrack(handle, id)
    }

    fun subtitle(): Int = binding.video.videoGetSpu(handle)

    fun subtitle(id: Int) {
        binding.video.videoSetSpu(handle, id)
    }

    private fun read(media: Pointer): List<VlcTrack> {
        val array = PointerByReference()
        val count: Int = binding.media.mediaTracksGet(media, array)
        val first: Pointer = array.value.takeIf { count > 0 } ?: return emptyList()

        val tracks: List<VlcTrack> = first.getPointerArray(0, count).map(::describe)
        binding.media.mediaTracksRelease(first, count)
        return tracks
    }

    private fun describe(pointer: Pointer): VlcTrack {
        val track = LibVlcMediaTrack(pointer)
        val type: VlcTrackType = VlcTrackType.of(track.type)
        val video: LibVlcVideoTrack? = detail(track, VlcTrackType.VIDEO, type)?.let(::LibVlcVideoTrack)
        val audio: LibVlcAudioTrack? = detail(track, VlcTrackType.AUDIO, type)?.let(::LibVlcAudioTrack)

        return VlcTrack(
            id = track.id,
            type = type,
            codecName = fourccOf(track.codec),
            language = track.language,
            description = track.description,
            bitrate = track.bitrate,
            width = video?.width ?: 0,
            height = video?.height ?: 0,
            channels = audio?.channels ?: 0,
        )
    }

    // The union, read only as what the track says it is. Reading a video struct
    // out of an audio track's union would answer a sample rate as a height.
    private fun detail(
        track: LibVlcMediaTrack,
        wanted: VlcTrackType,
        actual: VlcTrackType,
    ): Pointer? = track.detail.takeIf { actual == wanted }
}
