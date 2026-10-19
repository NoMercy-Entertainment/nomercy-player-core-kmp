// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.audioMix
import platform.AVFoundation.currentItem
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 1
private const val SETTLE_SECONDS = 0.5
private const val BYTES_PER_SAMPLE = 2

// The equaliser tap has to reach the item that plays, or the graph is inert.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AVPlayerAudioMixTest {

    private fun media(name: String): String {
        val path = "${NSTemporaryDirectory()}$name.wav"
        check(writeSilentWav(path, SECONDS)) { "could not write the gate media to $path" }
        return NSURL.fileURLWithPath(path).absoluteString ?: error("could not build a file URL")
    }

    private fun settle() {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(SETTLE_SECONDS))
    }

    @Test
    fun aLoadedItemCarriesTheGraphsAudioMix() {
        val backend = AVPlayerAudioBackend()

        try {
            runBlocking { backend.load(media("mix-a"), LoadOptions()) }
            settle()

            assertNotNull(backend.currentItemAudioMix(), "the item has no audio mix, so the equaliser hears nothing")
        } finally {
            backend.release()
        }
    }

    @Test
    fun aBackendWithoutAGraphLeavesTheItemUntouched() {
        val backend = AVPlayerVideoBackend()

        try {
            runBlocking { backend.load(media("mix-b"), LoadOptions()) }
            settle()

            assertNull(backend.avPlayer.currentItem?.audioMix, "a backend with no graph attached a mix anyway")
        } finally {
            backend.release()
        }
    }

    private fun writeSilentWav(path: String, seconds: Int): Boolean {
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        val dataSize = byteRate * seconds
        val bytes = ArrayList<Byte>(44 + dataSize)

        fun ascii(text: String) = text.forEach { bytes.add(it.code.toByte()) }
        fun int32(value: Int) = repeat(4) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }
        fun int16(value: Int) = repeat(2) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }

        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(1)
        int32(SAMPLE_RATE); int32(byteRate); int16(BYTES_PER_SAMPLE); int16(16)
        ascii("data"); int32(dataSize)
        repeat(dataSize) { bytes.add(0) }

        val array: ByteArray = bytes.toByteArray()
        return array.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = array.size.toULong())
                .writeToFile(path, atomically = true)
        }
    }
}
