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

    /** Null when the property is unset or unknown. The caller frees nothing: JNA copies. */
    public fun mpv_get_property_string(handle: MpvHandle, name: String): String?

    /**
     * A command is an argv, NUL-terminated. `loadfile`, `seek`, `stop` — the
     * verbs that are not properties.
     */
    public fun mpv_command(handle: MpvHandle, args: Array<String?>): Int

    public fun mpv_error_string(error: Int): String

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
