// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType

/**
 * libmpv, as much of it as a player needs.
 *
 * Seven functions against vlcj's whole object graph, because mpv's API is a
 * property bag rather than a class hierarchy: every question — which rendition,
 * which audio track, which subtitle, the playhead, whether it is paused — is
 * `get_property` and `set_property` on a string name.
 *
 * This is what closes quality switching. libVLC 3 has no call that pins a
 * rendition, so `VlcjVideoBackend.quality()` returns without doing anything for
 * every HLS stream, and the menu built from the manifest could never select
 * against it. mpv exposes each variant as an `edition` and takes an assignment:
 * measured 1:1 across bipbop's six rungs — 416x234, 640x360, 960x540, 1280x720,
 * 1920x1080, and an audio-only rung that yields no video — from the UNMODIFIED
 * master playlist. No rewriter, no temp file, no maxheight approximation.
 */

/** An `mpv_handle*`. Opaque by design; every operation goes through a property. */
public class MpvHandle : PointerType {
    public constructor() : super()
    public constructor(pointer: Pointer?) : super(pointer)
}

/**
 * Property formats, as libmpv numbers them in client.h.
 *
 * Only the three a player asks for. `NODE` and the array formats exist and are
 * deliberately absent: reaching for them is how a binding grows a parser for a
 * structure the caller could have asked about field by field.
 */
public object MpvFormat {
    public const val NONE: Int = 0
    public const val STRING: Int = 1
    public const val FLAG: Int = 3
    public const val INT64: Int = 4
    public const val DOUBLE: Int = 5
}

public interface LibMpv : Library {

    public fun mpv_create(): MpvHandle?

    public fun mpv_initialize(handle: MpvHandle): Int

    public fun mpv_terminate_destroy(handle: MpvHandle)

    /**
     * Returns 0 on success and a negative error code otherwise, which is the
     * whole of mpv's error convention. A caller that ignores it gets a player
     * that silently did not do the thing it was asked to.
     */
    public fun mpv_set_option_string(handle: MpvHandle, name: String, data: String): Int

    public fun mpv_set_property_string(handle: MpvHandle, name: String, data: String): Int

    /**
     * Null when the property is unset or unknown. libmpv allocated the bytes and
     * the CALLER frees them with [mpv_free].
     *
     * A `Pointer` rather than a `String`, which is what this was: JNA copies a
     * returned `char*` into a Kotlin string and forgets the original, so every
     * call leaked. That is invisible in a test that reads one property and
     * costs an evening's memory in a poll loop asking for the playhead four
     * times a second. Use [property] rather than calling this directly.
     */
    public fun mpv_get_property_string(handle: MpvHandle, name: String): Pointer?

    /**
     * A command is an argv, NUL-terminated. `loadfile`, `seek`, `stop` — the
     * verbs that are not properties.
     */
    public fun mpv_command(handle: MpvHandle, args: Array<String?>): Int

    public fun mpv_error_string(error: Int): String

    /**
     * Frees a string libmpv allocated.
     *
     * Only [mpv_get_property_string]'s result needs it, and JNA hides that:
     * mapping the return as `String` makes JNA copy the bytes and forget the
     * pointer, so the original leaks once per call. A poll loop asking for the
     * playhead four times a second leaks all evening. Declared here so the
     * pointer-returning overload can be used where that matters.
     */
    public fun mpv_free(data: Pointer)

    public companion object {
        /**
         * The soname differs per platform and none of them is "mpv".
         *
         * Windows ships `libmpv-2.dll`, Linux `libmpv.so.2`, macOS
         * `libmpv.2.dylib`. JNA's own resolution finds none of those from the
         * bare name, and the failure reads as "library not found" rather than
         * "you asked for the wrong name".
         */
        public val SONAME: String = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "libmpv-2"
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "mpv.2"
            else -> "mpv.so.2"
        }

        public fun load(): LibMpv = Native.load(SONAME, LibMpv::class.java)
    }
}

/**
 * A property as a string, copied out and freed.
 *
 * Every read of a libmpv property goes through here. The pair of calls is easy
 * to write once and easy to forget the second half of, and forgetting it is a
 * leak rather than a failure — nothing reports it and nothing breaks until the
 * machine is out of memory.
 */
public fun LibMpv.property(handle: MpvHandle, name: String): String? {
    val pointer: Pointer = mpv_get_property_string(handle, name) ?: return null
    return try {
        pointer.getString(0)
    } finally {
        mpv_free(pointer)
    }
}
