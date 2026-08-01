// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.nio.ByteBuffer

// libVLC's `vmem` video output, wired to a buffer this process owns.
//
// Five callbacks and one buffer. libVLC asks for a format, is given somewhere to
// decode into, and says when a frame is complete; the sink copies it out. There
// is no window, no toolkit and no X connection anywhere in that path, which is
// what lets the desktop engine be constructed on a machine that has no display.
internal class VlcVideoOutput(
    private val binding: LibVlcBinding,
    private val handle: Pointer,
    private val sink: VlcVideoFrameSink,
) {

    // The allocation, kept so it is not collected while libVLC is decoding into
    // it, and the aligned view of it that libVLC is actually given.
    private var allocation: Memory? = null
    private var plane: Pointer? = null
    private var frame: ByteBuffer? = null

    // Held as fields for the same reason the event callback is: JNA keeps no
    // reference, and a collected callback with a live native address is a crash.
    private val setup = VlcVideoFormatCallback { _, chroma, width, height, pitches, lines ->
        negotiate(chroma, pitches, lines, PictureSize(width.getInt(0), height.getInt(0)))
    }

    private val cleanup = VlcVideoCleanupCallback { forget() }

    private val lock = VlcVideoLockCallback { _, planes ->
        planes.setPointer(0, plane)
        // The picture identifier, which this output has no use for: there is one
        // buffer and display() already knows where it is.
        Pointer.NULL
    }

    private val unlock = VlcVideoUnlockCallback { _, _, _ -> }

    private val display = VlcVideoDisplayCallback { _, _ -> frame?.let(sink::display) }

    init {
        binding.video.videoSetFormatCallbacks(handle, setup, cleanup)
        binding.video.videoSetCallbacks(handle, lock, unlock, display, null)
    }

    // RV32, which is BGRA in memory on a little-endian machine and therefore
    // lands in a Skia BGRA_8888 bitmap with a copy and no conversion.
    //
    // A tightly packed stride is asked for — width times four, no row padding —
    // because a sink that had to honour an arbitrary pitch would be copying row
    // by row instead of in one go.
    private fun negotiate(
        chroma: Pointer,
        pitches: Pointer,
        lines: Pointer,
        picture: PictureSize,
    ): Int {
        val stride: Int = picture.width * BYTES_PER_PIXEL

        chroma.write(0, RV32, 0, RV32.size)
        pitches.setInt(0, stride)
        lines.setInt(0, picture.height)

        allocate(stride.toLong() * picture.height)
        sink.format(picture.width, picture.height)
        return ONE_PLANE
    }

    private class PictureSize(val width: Int, val height: Int)

    // Aligned by hand, because libVLC's decoders write frames with vector
    // instructions that want a 32-byte boundary and malloc only promises
    // sixteen. The slack is allocated up front and the pointer handed over is
    // the first aligned address inside it.
    private fun allocate(bytes: Long) {
        val block = Memory(bytes + ALIGNMENT)
        val offset: Long = (ALIGNMENT - Pointer.nativeValue(block) % ALIGNMENT) % ALIGNMENT
        val aligned: Pointer = block.share(offset, bytes)

        allocation = block
        plane = aligned
        frame = aligned.getByteBuffer(0, bytes)
    }

    // libVLC has finished with the format. The buffer is dropped rather than
    // freed: the display callback can still be in flight on another thread, and
    // freeing under it would be a use-after-free instead of a stale frame.
    private fun forget() {
        frame = null
        plane = null
        allocation = null
    }

    private companion object {
        const val BYTES_PER_PIXEL: Int = 4
        const val ONE_PLANE: Int = 1
        const val ALIGNMENT: Long = 32

        // Four characters, no terminator: libVLC gives the callback a char[4].
        val RV32: ByteArray = "RV32".toByteArray(Charsets.US_ASCII)
    }
}
