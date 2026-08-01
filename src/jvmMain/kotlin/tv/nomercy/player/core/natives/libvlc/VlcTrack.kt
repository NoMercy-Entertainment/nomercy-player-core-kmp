// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

// What kind of stream a track is, as libvlc_track_type_t numbers it.
internal enum class VlcTrackType(val id: Int) {
    UNKNOWN(-1),
    AUDIO(0),
    VIDEO(1),
    TEXT(2),
    ;

    companion object {
        fun of(id: Int): VlcTrackType = entries.firstOrNull { type -> type.id == id } ?: UNKNOWN
    }
}

// One stream inside the current item, copied out of libVLC's memory.
//
// Copied rather than referenced, because libvlc_media_tracks_release frees the
// array the moment the read is over — a class holding pointers into it would be
// reading somebody else's memory by the time a menu was drawn.
//
// Zero is the honest answer for a field this kind of track does not have, and
// for a field libVLC has not measured: bitrate is 0 on most local files.
internal data class VlcTrack(
    val id: Int,
    val type: VlcTrackType,
    val codecName: String?,
    val language: String?,
    val description: String?,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val channels: Int,
)

// libVLC's fourcc, as the four characters it is.
//
// "h264", "hevc", "mp4a", "a52" — the vocabulary VlcTrackMapper translates from,
// and the same rendering the previous binding produced: the four bytes of the
// integer, little end first, trimmed of the padding space a three-letter codec
// carries.
//
// Null for zero, which is libVLC's way of saying it has no codec for this track.
internal fun fourccOf(codec: Int): String? {
    if (codec == 0) return null
    val bytes = ByteArray(FOURCC_BYTES) { index -> (codec ushr (index * Byte.SIZE_BITS)).toByte() }
    return String(bytes, Charsets.ISO_8859_1).trim()
}

private const val FOURCC_BYTES: Int = 4
