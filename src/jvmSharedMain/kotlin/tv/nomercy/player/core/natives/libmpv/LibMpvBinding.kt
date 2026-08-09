// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Library
import com.sun.jna.NativeLibrary
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.ptr.PointerByReference
import tv.nomercy.player.core.natives.HostPlatform
import tv.nomercy.player.core.natives.NativeRuntimeKind
import tv.nomercy.player.core.natives.NativeRuntimes

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

// The names are C's, verbatim, and the interface is as wide as the API a player
// needs. A binding that renamed mpv_get_property_string to getPropertyString
// would be one more thing to translate when reading mpv's own documentation
// beside this file.
@Suppress("FunctionNaming", "ComplexInterface")
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

    // ---- the render API ----------------------------------------------------
    //
    // Software rendering, not OpenGL. Skiko already runs SOFTWARE on this
    // desktop and the picture has to reach a Compose bitmap either way, so a GL
    // context would be a texture created to be read straight back — and a GL
    // vout on the desktop is a native child window that Compose cannot compose.

    /**
     * Creates a render context. [params] is a contiguous array of
     * `mpv_render_param` terminated by a zeroed one.
     *
     * Returns 0 on success. The context and the handle are separate lifetimes:
     * the context must be freed BEFORE the handle it was made from.
     */
    public fun mpv_render_context_create(context: PointerByReference, handle: MpvHandle, params: Pointer): Int

    /** Draws the current frame into whatever the params point at. */
    public fun mpv_render_context_render(context: Pointer, params: Pointer): Int

    public fun mpv_render_context_free(context: Pointer)

    // ---- the event queue ---------------------------------------------------
    //
    // State is polled, and stays polled. This is here for the one thing a poll
    // cannot produce: a stream that fails to open leaves every property at the
    // value it had before the load, so "broken" and "still loading" read
    // identically and the player above spins forever. mpv says which, once,
    // through END_FILE, and nowhere else.

    /**
     * The next event, or an event of [MpvEventId.NONE] when [timeoutSeconds]
     * elapsed first.
     *
     * Never null in practice; mpv returns a pointer to storage it owns and
     * reuses, so the result must be read before the next call and never kept.
     * A finite timeout is what makes this safe to park a thread in — the call
     * returns on its own, and [mpv_wakeup] cuts it short at shutdown.
     *
     * Must be called from ONE thread only. mpv's queue is not multi-consumer.
     */
    public fun mpv_wait_event(handle: MpvHandle, timeoutSeconds: Double): MpvEvent.ByReference?

    /** Returns [mpv_wait_event] immediately, which is how the pump is stopped. */
    public fun mpv_wakeup(handle: MpvHandle)

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
            HostPlatform.isAndroid() -> "mpv"
            else -> "mpv.so.2"
        }

        // ffmpeg, in link order, before libmpv on Android.
        //
        // Debian and Homebrew hand us a libmpv with ffmpeg inside it; the
        // mpv-android buildscripts hand us eight shared objects. Android's
        // linker resolves a DT_NEEDED against libraries already loaded in the
        // namespace, and it will not search a directory we extracted at run
        // time, so each dependency is opened by absolute path first and libmpv
        // finds them by soname afterwards. Order matters — a library loaded
        // before the one it needs fails on its own DT_NEEDED.
        private val ANDROID_DEPENDENCIES: List<String> = listOf(
            // The NDK's C++ runtime first, because everything else needs it and
            // nothing on the device provides it. mpv-android links it
            // dynamically, so libmpv.so carries a DT_NEEDED that fails with a
            // message naming a library no Android system has ever had.
            "libc++_shared.so",
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavcodec.so",
            "libavformat.so",
            "libavfilter.so",
            "libavdevice.so",
        )

        /**
         * The bundled payload first, then whatever the machine has.
         *
         * Without this a consumer of the library has to install libmpv by hand
         * and point `jna.library.path` at it, which is precisely the "a library
         * that only works on a machine somebody already configured" problem the
         * payload store exists to remove — and it is how the desktop came to
         * draw a black rectangle on a stock Windows box.
         *
         * The system copy is still reachable: nothing here removes a search
         * path, so a developer with their own build on the path keeps using it.
         */
        public fun load(): LibMpv {
            NativeRuntimes.directory(NativeRuntimeKind.LIB_MPV)?.let { payload ->
                NativeLibrary.addSearchPath(SONAME, payload.absolutePath)
                if (HostPlatform.isAndroid()) loadAndroidDependencies(payload)
            }
            return Native.load(SONAME, LibMpv::class.java)
        }

        // Android resolves no transitive SONAMEs for a library loaded from a
        // payload directory, so libmpv's own dependencies are loaded by hand and
        // in order before it. Missing one surfaces as an UnsatisfiedLinkError
        // naming libmpv rather than the library that was actually absent.
        private fun loadAndroidDependencies(payload: java.io.File) {
            ANDROID_DEPENDENCIES.forEach { name ->
                val library: java.io.File = java.io.File(payload, name)
                if (library.isFile) System.load(library.absolutePath)
            }
        }
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
