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
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The HDMI sink of a TV box says yes to every bitstream the TV's EDID lists.
// A sink that says yes to everything stands in for it here.
@OptIn(UnstableApi::class)
class StereoBitstreamDecodingSinkTest {

    private val sayYes: AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "supportsFormat" -> true
            "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else -> null
        }
    } as AudioSink

    private val sink = StereoBitstreamDecodingSink(sayYes)

    private fun audio(mime: String, channels: Int): Format =
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48_000).build()

    @Test
    fun stereo_eac3_is_refused_so_the_renderer_decodes_it_to_pcm() {
        val ddp20 = audio(MimeTypes.AUDIO_E_AC3, 2)
        assertFalse(sink.supportsFormat(ddp20))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ddp20))
    }

    @Test
    fun stereo_and_mono_ac3_are_refused_too() {
        assertFalse(sink.supportsFormat(audio(MimeTypes.AUDIO_AC3, 2)))
        assertFalse(sink.supportsFormat(audio(MimeTypes.AUDIO_AC3, 1)))
    }

    @Test
    fun surround_bitstream_still_passes_through() {
        val ddp51 = audio(MimeTypes.AUDIO_E_AC3, 6)
        assertTrue(sink.supportsFormat(ddp51))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ddp51))
        assertTrue(sink.supportsFormat(audio(MimeTypes.AUDIO_E_AC3_JOC, 2)))
    }

    @Test
    fun pcm_is_untouched() {
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(2)
            .setSampleRate(48_000).setPcmEncoding(androidx.media3.common.C.ENCODING_PCM_16BIT).build()
        assertTrue(sink.supportsFormat(pcm))
    }
}
