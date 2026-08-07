// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * `mpv_render_param[]`, packed by hand.
 *
 * libmpv takes its render options as a NUL-terminated array of
 * `{ int type; void *data; }`. Two things about that are easy to get wrong and
 * silent when wrong: the struct is PADDED to sixteen bytes on a 64-bit ABI, so
 * writing the pointer at offset four hands mpv a garbage address; and the array
 * must end with a zeroed entry, without which mpv walks off the end of it. Both
 * failures are a crash inside native code with a stack naming none of this.
 *
 * The [Memory] blocks are held for the life of the builder on purpose. JNA frees
 * native memory when its wrapper is collected, and mpv keeps reading these
 * across the whole render call — a parameter block collected mid-render is the
 * same crash by a slower route.
 */
internal class MpvRenderParams {

    private val values: MutableList<Pair<Int, Pointer?>> = mutableListOf()
    private val held: MutableList<Memory> = mutableListOf()

    fun string(type: Int, value: String) = apply {
        val bytes: ByteArray = value.toByteArray(Charsets.UTF_8)
        val block = Memory((bytes.size + 1).toLong())
        block.write(0, bytes, 0, bytes.size)
        block.setByte(bytes.size.toLong(), 0)
        held += block
        values += type to block
    }

    fun ints(type: Int, vararg value: Int) = apply {
        val block = Memory((value.size * Int.SIZE_BYTES).toLong())
        value.forEachIndexed { index, item -> block.setInt((index * Int.SIZE_BYTES).toLong(), item) }
        held += block
        values += type to block
    }

    /**
     * A `size_t`, which is EIGHT bytes here and not four.
     *
     * SW_STRIDE is the one parameter mpv reads as a size_t. Written as an int it
     * gives mpv a stride with four bytes of whatever followed it, and the whole
     * render call comes back "invalid parameter" — which reads as a wrong format
     * or a wrong size, the two things that are actually right.
     */
    fun size(type: Int, value: Long) = apply {
        val block = Memory(Native.SIZE_T_SIZE.toLong())
        when (Native.SIZE_T_SIZE) {
            Long.SIZE_BYTES -> block.setLong(0, value)
            else -> block.setInt(0, value.toInt())
        }
        held += block
        values += type to block
    }

    fun pointer(type: Int, value: Pointer) = apply {
        values += type to value
    }

    /**
     * The same array, built by JNA rather than by hand.
     *
     * A hand-packed version of this was written first and was byte-for-byte
     * correct — mpv accepted it at create time and refused every render, which
     * sent the hunt to the layout for an hour. The fault was the enum values,
     * not the packing. This stays because JNA computing the padding from the
     * field declarations is one fewer thing to be wrong about, and there is no
     * reason to keep two packers.
     */
    fun pack(): Pointer {
        val entries: Array<Structure> = MpvRenderParam().toArray(values.size + 1)
        values.forEachIndexed { index, (type, data) ->
            val entry = entries[index] as MpvRenderParam
            entry.type = type
            entry.data = data
            entry.write()
        }
        val terminator = entries[values.size] as MpvRenderParam
        terminator.type = MpvRenderParamType.INVALID
        terminator.data = null
        terminator.write()
        held += Memory(1)
        structures += entries
        return entries[0].pointer
    }

    private val structures: MutableList<Array<Structure>> = mutableListOf()

    private companion object {
        // sizeof(void*) on this JVM, and the offset the pointer field lands at
        // once the int before it has been padded to the pointer's alignment.
        val POINTER_OFFSET: Long = Native.POINTER_SIZE.toLong()
        val ENTRY_BYTES: Long = POINTER_OFFSET * 2
    }
}

/**
 * The parameter types this player uses, as libmpv numbers them in render.h.
 *
 * Only the software-rendering set. The OpenGL and DRM parameters exist and are
 * deliberately absent: a binding that lists everything invites a caller to
 * reach for a path nothing here has ever run.
 */
internal object MpvRenderParamType {
    const val INVALID: Int = 0
    const val API_TYPE: Int = 1
    // Read out of render.h in the payload rather than from memory of the enum.
    // The first pass numbered these 20..23 from a stale listing, which is what
    // MPV_RENDER_PARAM_DRM_* occupy — so mpv found no software parameters at
    // all and refused every frame with "invalid parameter", a message that
    // names neither the parameter nor the fact that it never saw one.
    const val SW_SIZE: Int = 17
    const val SW_FORMAT: Int = 18
    const val SW_STRIDE: Int = 19
    const val SW_POINTER: Int = 20
}

/** mpv's own name for the software renderer, and the only one asked for here. */
internal const val MPV_RENDER_API_TYPE_SW: String = "sw"

/**
 * Four bytes per pixel, blue first, alpha ignored.
 *
 * mpv names its software formats by BYTE ORDER and accepts exactly four:
 * `rgb0`, `bgr0`, `0bgr`, `0rgb`. "bgra" is not one of them, and passing it
 * fails the whole render call with "invalid parameter" — a message that says
 * nothing about which parameter and sends you looking at the size.
 *
 * The same order libVLC's RV32 produces on a little-endian machine, which is
 * what [tv.nomercy.player.core.natives.libvlc.VlcVideoFrameSink] already
 * documents and what the Compose sink already unpacks. Choosing a different one
 * here would mean two pixel orders in one desktop and a picture with the reds
 * and blues swapped on whichever engine lost the argument.
 */
internal const val MPV_SW_FORMAT_BGRA: String = "bgr0"

/**
 * `mpv_render_param` as JNA sees it: an int and a pointer, padded by the ABI.
 *
 * `@Structure.FieldOrder` is not optional. Without it JNA orders the fields by
 * reflection, which is unspecified, and a struct whose fields swap places is a
 * pointer read as an int — accepted by nothing and reported as an invalid
 * parameter.
 */
@Structure.FieldOrder("type", "data")
internal open class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}

