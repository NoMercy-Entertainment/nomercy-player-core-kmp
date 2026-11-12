// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Issue #75: on 64-bit macOS AudioBufferList is { UInt32 mNumberBuffers; 4 bytes
// padding; AudioBuffer mBuffers[] } with AudioBuffer { UInt32 mNumberChannels;
// UInt32 mDataByteSize; void* mData }, so the sizes sit at 12 and the pointer at 16.
class CoreAudioBufferListLayoutTest {

    private val channels = 2
    private val samples = floatArrayOf(0.25f, -0.5f, 0.75f, -1.0f)

    private fun bufferList(): Memory {
        val data = Memory((samples.size * Float.SIZE_BYTES).toLong())
        data.write(0, samples, 0, samples.size)
        val list = Memory(BUFFER_LIST_SIZE)
        list.clear()
        list.setInt(0, 1)
        list.setInt(MBUFFERS_OFFSET.toLong(), channels)
        list.setInt((MBUFFERS_OFFSET + 4).toLong(), samples.size * Float.SIZE_BYTES)
        list.setPointer((MBUFFERS_OFFSET + 8).toLong(), data)
        return list
    }

    @Test
    fun frameCountReadsTheDataByteSizeField() {
        assertEquals(samples.size / channels, bufferList().frameCount(channels))
    }

    @Test
    fun samplesAreReadFromTheDataPointerField() {
        val frames = samples.size / channels

        assertContentEquals(samples, bufferList().readInterleavedFloat(channels, frames))
    }

    private companion object {
        const val MBUFFERS_OFFSET = 8
        const val BUFFER_LIST_SIZE = 32L
    }
}
