// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemLegibleOutput
import platform.AVFoundation.AVPlayerItemLegibleOutputPushDelegateProtocol
import platform.AVFoundation.addOutput
import platform.AVFoundation.outputs
import platform.CoreMedia.CMTime
import platform.Foundation.NSAttributedString
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import tv.nomercy.player.core.events.SubtitleCue

// The cues AVFoundation has decoded, handed over instead of drawn.
//
// AVPlayer paints the selected legible track itself, over the video layer and
// under nothing — which is why the Apple player looked like it had subtitles
// when the library had none: the platform was drawing them and the library never
// saw a cue. That is fine until a viewer changes the size, the colour or the
// background, because those settings live in the player and reach a renderer,
// not a system layer.
//
// So the output is asked for the text AND told to stop the player rendering it.
// The same trade the web backend makes when it holds a selected text track at
// `mode: 'hidden'`: the cues keep arriving, the platform stops painting, and one
// renderer draws every source the same way.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AVLegibleCues(
    private val announce: (List<SubtitleCue>) -> Unit,
) : NSObject(), AVPlayerItemLegibleOutputPushDelegateProtocol {

    // Its own queue, for the same reason the periodic time observer has one: the
    // main queue reports nothing while the main thread is busy, and a caption
    // that arrives late is a caption on the wrong frame.
    private val queue = dispatch_queue_create("tv.nomercy.player.avplayer.legible", null)

    private val output: AVPlayerItemLegibleOutput = AVPlayerItemLegibleOutput().apply {
        setDelegate(this@AVLegibleCues, queue)
        suppressesPlayerRendering = true
    }

    // Per item, because an output belongs to the item it was added to.
    // AVFoundation throws when the same output is added twice, so an item that
    // already carries this one is left alone.
    fun attach(item: AVPlayerItem) {
        if (output !in item.outputs) item.addOutput(output)
    }

    // AVFoundation reports an EMPTY array at the moment a cue ends, which is the
    // signal a renderer needs to clear the picture. Forwarded rather than
    // filtered out: swallowing it leaves the last line of dialogue on screen
    // until the next one replaces it.
    override fun legibleOutput(
        output: AVPlayerItemLegibleOutput,
        didOutputAttributedStrings: List<*>,
        nativeSampleBuffers: List<*>,
        forItemTime: CValue<CMTime>,
    ) {
        announce(didOutputAttributedStrings.mapNotNull(::cueOf))
    }

    // The text, and the layout defaults.
    //
    // AVFoundation carries WebVTT's line, position, size and alignment as
    // CoreMedia text-markup attributes on the attributed string rather than as
    // fields, and reading them means bridging CFString keys out of an
    // NSAttributedString. That is not done here, so an Apple cue lands where a
    // cue with no instructions goes — near the bottom, centred — and a forced
    // sign placed at the top of the frame by the file will not be. Stated rather
    // than hidden: the text is on screen and correctly timed, and the
    // positioning is a known gap on this platform alone.
    private fun cueOf(entry: Any?): SubtitleCue? {
        val text: String = (entry as? NSAttributedString)?.string ?: return null
        return if (text.isEmpty()) null else SubtitleCue(text = text, plainText = text)
    }
}
