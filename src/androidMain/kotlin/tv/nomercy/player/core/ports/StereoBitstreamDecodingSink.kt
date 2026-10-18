// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

// Refuses a stereo or mono bitstream so the renderer decodes it to PCM.
// Passthrough adds nothing at two channels and breaks on a plain-ARC soundbar.
@OptIn(UnstableApi::class)
internal class StereoBitstreamDecodingSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean =
        !isStereoBitstream(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (isStereoBitstream(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    internal companion object {
        private val ENCODED = setOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_AC4,
        )

        fun isStereoBitstream(format: Format): Boolean =
            format.sampleMimeType in ENCODED && format.channelCount in 1..2
    }
}
