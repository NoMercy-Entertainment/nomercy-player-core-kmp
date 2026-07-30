// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import kotlin.concurrent.Volatile

// Asks the DECODER to hand back SDR frames, rather than converting them after it.
//
// This is the tone-map that costs nothing per frame: MediaCodec does it in the
// same hardware pass that decodes, and a codec that cannot honour the request
// ignores it. The alternative on this platform is Media3's OpenGL effects
// pipeline, which means routing every frame of every item through a video graph
// so that a rare case can be handled — a device-compatibility and battery cost
// paid by all playback to fix some of it. Media3 exposes that path through
// PlaybackVideoGraphWrapper.setRequestOpenGlToneMapping and it is deliberately
// not used here.
//
// The seam is DefaultRenderersFactory.getCodecAdapterFactory(), which
// buildVideoRenderers feeds into MediaCodecVideoRenderer.Builder — so one
// override reaches the video decoder without replacing the renderer.
@OptIn(UnstableApi::class)
internal class ToneMappingCodecAdapterFactory(context: Context) : MediaCodecAdapter.Factory {

    private val delegate: MediaCodecAdapter.Factory = MediaCodecAdapter.Factory.getDefault(context)

    // Written from the main thread when a decision changes and read on the
    // playback thread as a codec is configured, which are not the same thread.
    @Volatile
    var toneMapToSdr: Boolean = false

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (shouldRequestSdr(configuration)) {
            configuration.mediaFormat.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            )
        }

        return delegate.createAdapter(configuration)
    }

    // One factory serves audio and video codecs alike, so the request is narrowed
    // to a video decoding configuration carrying an HDR transfer. Asking an audio
    // codec for a colour transfer is meaningless, and asking an already-SDR video
    // codec for one is a MediaFormat key that changes nothing but appears in every
    // codec log as though it did.
    private fun shouldRequestSdr(configuration: MediaCodecAdapter.Configuration): Boolean =
        toneMapToSdr &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            configuration.surface != null &&
            ColorInfo.isTransferHdr(configuration.format.colorInfo)

    internal companion object {
        // KEY_COLOR_TRANSFER_REQUEST arrives in Android 12. Below it there is no
        // way to ask a decoder for a converted picture at all, which is what
        // ExoPlayerVideoBackend reports through canToneMapHdrToSdr rather than
        // requesting something the framework will not read.
        const val MIN_SDK: Int = Build.VERSION_CODES.S
    }
}
