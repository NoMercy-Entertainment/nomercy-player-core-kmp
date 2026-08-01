// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Pointer
import com.sun.jna.Structure

// libvlc_media_track_t, field for field.
//
// `detail` is the C union of three pointers — audio, video, subtitle — and a
// union of pointers is a pointer, so it is bound as one and read through the
// struct that `type` says it is. Modelling the union itself would buy nothing
// and cost a second place for the layout to drift.
//
// The fields after it matter as much as the union: `bitrate`, `language` and
// `description` sit AFTER it in the header, so a struct that stopped at the
// union would read the language out of the middle of a pointer.
@Structure.FieldOrder(
    "codec",
    "originalCodec",
    "id",
    "type",
    "profile",
    "level",
    "detail",
    "bitrate",
    "language",
    "description",
)
internal class LibVlcMediaTrack(pointer: Pointer) : Structure(pointer) {

    @JvmField
    var codec: Int = 0

    @JvmField
    var originalCodec: Int = 0

    @JvmField
    var id: Int = 0

    @JvmField
    var type: Int = 0

    @JvmField
    var profile: Int = 0

    @JvmField
    var level: Int = 0

    @JvmField
    var detail: Pointer? = null

    @JvmField
    var bitrate: Int = 0

    @JvmField
    var language: String? = null

    @JvmField
    var description: String? = null

    init {
        read()
    }
}

// libvlc_video_track_t, as far as this library reads it.
//
// Height and width are the first two fields and the only two anything here
// asks for. What follows them — aspect ratio, frame rate, orientation,
// projection, the 360 viewpoint — is not read, and declaring it would be
// six more chances to get an offset wrong for no answer gained.
//
// What is NOT here is the reason HDR renditions are identified from the HLS
// manifest instead: libvlc_video_track_t carries no colour primaries and no
// transfer function, in any VLC 3 release. There is nothing to bind.
@Structure.FieldOrder("height", "width")
internal class LibVlcVideoTrack(pointer: Pointer) : Structure(pointer) {

    @JvmField
    var height: Int = 0

    @JvmField
    var width: Int = 0

    init {
        read()
    }
}

// libvlc_audio_track_t. Channels first, sample rate second.
@Structure.FieldOrder("channels", "rate")
internal class LibVlcAudioTrack(pointer: Pointer) : Structure(pointer) {

    @JvmField
    var channels: Int = 0

    @JvmField
    var rate: Int = 0

    init {
        read()
    }
}
