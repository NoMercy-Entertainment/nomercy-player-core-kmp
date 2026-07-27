// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.CoreAudioTypes.AudioBufferList
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PreEffects
import tv.nomercy.player.core.dsp.EqBand
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugins.audio.VisualizationFrame

// The equaliser and the spectrum on Apple, through a tap on the audio mix.
//
// Not a rebuilt engine, and that is a recorded decision rather than a shortcut.
// The plan preferred replacing the whole transport with AVAudioEngine, on the
// grounds that it is the only path giving the crossfade a second player node —
// which is not true here: AVPlayerAudioBackend already runs two players through
// EqualPowerCrossfader, proven on the simulator. With that gone, the rebuild
// costs an HLS decode pump for a service that serves HLS, on a transport
// carrying seven hundred green tests.
//
// So the arithmetic is `PcmEqualiser`, shared with the desktop and measured in
// decibels there, and the only Apple-specific part is getting samples to it.
//
// The tap is fiddlier than a node graph and the awkwardness is real: it attaches
// to an AVAudioMix on the item's audio track, so it must be re-applied per item
// rather than configured once. That is written down rather than glossed.
@OptIn(ExperimentalForeignApi::class)
public class AppleDspGraph(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channels: Int = DEFAULT_CHANNELS,
) : AudioDspGraph {

    // The shared arithmetic. Apple contributes plumbing, not a second filter.
    private val equaliser = PcmEqualiser(sampleRate, channels)

    // Handed to the C callbacks through the tap's storage, because a
    // staticCFunction cannot capture anything. This is the only way the
    // callback finds its way back to this object.
    private val self: StableRef<AppleDspGraph> = StableRef.create(this)

    // Reused across callbacks rather than allocated per buffer. The process
    // callback runs on a realtime audio thread, where an allocation is a
    // deadline missed and a deadline missed is an audible gap.
    private var scratch: FloatArray = FloatArray(0)

    // The mix to hand to an AVPlayerItem, or null when the tap could not be
    // made. Null is answered honestly rather than a mix that does nothing:
    // a caller that attaches an inert mix has silently lost its equaliser.
    public fun audioMix(track: AVAssetTrack): AVMutableAudioMix? {
        val tap: MTAudioProcessingTapRef = createTap() ?: return null

        val parameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(track)
        parameters.setAudioTapProcessor(tap)

        val mix = AVMutableAudioMix.audioMix()
        mix.setInputParameters(listOf(parameters))
        return mix
    }

    private fun createTap(): MTAudioProcessingTapRef? = memScoped {
        val callbacks = alloc<MTAudioProcessingTapCallbacks>()
        callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
        callbacks.clientInfo = self.asCPointer()
        callbacks.init = null
        callbacks.finalize = null
        callbacks.prepare = null
        callbacks.unprepare = null
        callbacks.process = staticCFunction(::processTap)

        val out = alloc<CPointerVar<cnames.structs.opaqueMTAudioProcessingTap>>()
        val status: Int = MTAudioProcessingTapCreate(
            null,
            callbacks.ptr,
            kMTAudioProcessingTapCreationFlag_PreEffects.toUInt(),
            out.ptr,
        )
        if (status != 0) null else out.value
    }

    // Called from the realtime callback. Interleaved float is what the tap
    // hands over, which is the same shape the desktop's amem gives, so the
    // shared filter takes it unchanged.
    internal fun shape(buffer: CPointer<AudioBufferList>, frames: Int) {
        val list: AudioBufferList = buffer.pointed
        // mBuffers is a flexible array member, so cinterop exposes the first
        // element directly. Interleaved audio lives entirely in it — one buffer
        // holding every channel — which is the layout the tap delivers.
        val data: CPointer<FloatVar> = list.mBuffers[0].mData?.reinterpret() ?: return

        val needed: Int = frames * channels
        if (scratch.size < needed) scratch = FloatArray(needed)

        for (index in 0 until needed) scratch[index] = data[index]
        shapeSamples(scratch, frames)
        for (index in 0 until needed) data[index] = scratch[index]
    }

    // The same call the tap makes, reachable without a live AVPlayerItem.
    //
    // Not a test-only path: `shape` above is a pointer copy around this. A gate
    // that reimplemented the filtering to avoid the pointer would be measuring
    // its own arithmetic rather than the graph's, which is the shape of test
    // this project keeps catching.
    internal fun shapeSamples(samples: FloatArray, frames: Int) {
        equaliser.process(samples, frames)
    }

    // A seek makes the next buffer discontinuous, and a filter carrying history
    // across that rings.
    public fun flush(): Unit = equaliser.reset()

    // Every setting goes straight through. The graph interface is the same one
    // Android and the desktop implement, so a plugin above cannot tell which
    // platform it is on — which is the whole point of the seam.
    override fun setEqBands(bands: List<EqBand>): Unit = equaliser.setEqBands(bands)

    override fun bandGain(frequencyHz: Int, gainDb: Double): Unit = equaliser.bandGain(frequencyHz, gainDb)

    override fun preGain(linear: Double): Unit = equaliser.preGain(linear)

    override fun installFrameTap(onFrame: (VisualizationFrame) -> Unit): Subscription =
        equaliser.installFrameTap(onFrame)

    override fun removeFrameTap(): Unit = equaliser.removeFrameTap()

    override fun eqEnabled(enabled: Boolean): Unit = equaliser.eqEnabled(enabled)

    // The StableRef pins this object for as long as the tap can call back into
    // it. Releasing it while a tap is live would hand the audio thread a
    // dangling pointer, which is a crash rather than a wrong sound.
    public fun release() {
        self.dispose()
    }
}

// Top level because a staticCFunction cannot be a method and cannot capture.
// It finds its graph through the tap's storage, which is the pointer handed to
// clientInfo at creation.
@OptIn(ExperimentalForeignApi::class)
private fun processTap(
    tap: MTAudioProcessingTapRef?,
    numberFrames: Long,
    @Suppress("UNUSED_PARAMETER") flags: UInt,
    bufferListInOut: CPointer<AudioBufferList>?,
    numberFramesOut: CPointer<LongVar>?,
    flagsOut: CPointer<UIntVar>?,
) {
    if (tap == null || bufferListInOut == null) return

    // Pulls the samples the item is about to play into the buffer list. Without
    // this the list is empty and the filter would shape nothing while appearing
    // to work.
    val status: Int = MTAudioProcessingTapGetSourceAudio(
        tap,
        numberFrames,
        bufferListInOut,
        flagsOut,
        null,
        numberFramesOut,
    )
    if (status != 0) return

    val storage: COpaquePointer = MTAudioProcessingTapGetStorage(tap) ?: return
    storage.asStableRef<AppleDspGraph>().get().shape(bufferListInOut, numberFrames.toInt())
}

private const val DEFAULT_SAMPLE_RATE = 44_100
private const val DEFAULT_CHANNELS = 2
