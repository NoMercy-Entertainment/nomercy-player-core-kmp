// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.device

// Probed rather than declared, same as every other actual of this port:
// MediaSource.isTypeSupported is the browser's own real answer for what its
// MSE pipeline can demux+decode, not a guess about "what browsers usually
// support". H264 is checked first — the server transcodes to the first
// listed codec, and it is the one every Cast/STB target reliably opens.
@JsFun(
    """(mime) => {
        try {
            return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime);
        } catch (e) {
            return false;
        }
    }""",
)
private external fun jsIsTypeSupported(mime: JsString): Boolean

@JsFun("() => window.screen.width * (window.devicePixelRatio || 1)")
private external fun jsScreenWidth(): Double

@JsFun("() => window.screen.height * (window.devicePixelRatio || 1)")
private external fun jsScreenHeight(): Double

private fun supported(mime: String): Boolean = jsIsTypeSupported(mime.toJsString())

public actual fun platformClientCapabilities(): ClientCapabilities {
    val videoCodecs = buildList {
        if (supported("""video/mp4; codecs="avc1.640028"""")) add(ClientCodec.H264)
        if (supported("""video/mp4; codecs="hvc1.1.6.L93.B0"""")) add(ClientCodec.H265)
        if (supported("""video/mp4; codecs="av01.0.05M.08"""")) add(ClientCodec.AV1)
    }
    val audioCodecs = buildList {
        if (supported("""audio/mp4; codecs="mp4a.40.2"""")) add(ClientCodec.AAC)
        if (supported("""audio/mp4; codecs="ec-3"""")) add(ClientCodec.EAC3)
        if (supported("""audio/mp4; codecs="ac-3"""")) add(ClientCodec.AC3)
        if (supported("""audio/mp4; codecs="flac"""")) add(ClientCodec.FLAC)
        if (supported("""audio/webm; codecs="opus"""")) add(ClientCodec.OPUS)
        if (supported("""audio/mpeg""")) add(ClientCodec.MP3)
    }
    // No MediaSource probe for these: HLS/DASH are manifest formats MSE
    // consumes segment-by-segment (there is no "video/vnd.apple.mpegurl" MIME
    // MSE itself accepts), so declaring them tracks what nomercy-video-player's
    // existing JS library already ships against, the same browser engine.
    val containers = listOf(ClientContainer.HLS, ClientContainer.MP4, ClientContainer.DASH)

    return ClientCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        containers = containers,
        maxWidth = runCatching { ClientResolution.clamp(jsScreenWidth().toInt()) }.getOrNull(),
        maxHeight = runCatching { ClientResolution.clamp(jsScreenHeight().toInt()) }.getOrNull(),
        // Not probed. No browser API answers whether the attached TV panel
        // itself does HDR — same "say no rather than guess" call every other
        // actual of this port makes for the same reason.
        supportsHdr = false,
        supports10Bit = supported("""video/mp4; codecs="hvc1.2.4.L93.B0""""),
        maxAudioChannels = STEREO,
        maxBitrateKbps = NO_CAP,
    )
}
