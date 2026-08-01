// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import java.util.EnumMap

// The native libraries the desktop engine needs, and where this machine's copy
// of each one is.
//
// A KMP library that only works on a machine somebody already configured is not
// a library, it is a set of instructions. Android gets its decoder inside the
// AAR and Apple gets its subtitle renderer inside the XCFramework; the JVM
// target was the one still asking the user to install VLC by hand, and styled
// subtitles were silently doing nothing unless the machine happened to have
// libass. Both holes are the same hole: a native dependency that arrives
// through the machine instead of through the dependency.
//
// So it arrives through the dependency. Each payload is a prebuilt archive for
// one operating system and one architecture, pinned by digest, unpacked once
// into a per-user cache and loaded from there — the same arrangement the Apple
// targets already use for libass, moved from build time to first use because a
// published JVM library has no build step in a consumer's project to hook.
public enum class NativeRuntimeKind(internal val slug: String) {
    // libVLC and its plugins: the desktop decoder.
    LIB_VLC("libvlc"),

    // libass: styled ASS and SSA subtitles, the ones that carry positioning,
    // fonts and karaoke. Without it those render as plain text or not at all.
    LIB_ASS("libass"),
}

public object NativeRuntimes {

    // Where this kind's native files are, or null when there is no payload for
    // this machine and nothing pre-staged.
    //
    // Null is not a failure to report as a crash. Every caller has a fallback —
    // libVLC falls back to a system install, libass falls back to unstyled
    // subtitles — and the reason is available separately for anything that
    // wants to say why.
    public fun directory(kind: NativeRuntimeKind): File? = resolved(kind).directory

    // Why [directory] answered null, in a sentence a developer can act on.
    // Null when it did not.
    public fun whyUnavailable(kind: NativeRuntimeKind): String? = resolved(kind).reason

    // Once per kind per process. Installing means a digest check over sixty
    // megabytes and possibly a download, and a question asked twice would pay
    // for it twice.
    private val cache: MutableMap<NativeRuntimeKind, NativeInstall> =
        EnumMap(NativeRuntimeKind::class.java)

    private fun resolved(kind: NativeRuntimeKind): NativeInstall = synchronized(cache) {
        cache.getOrPut(kind) { NativePayloadStore.install(kind) }
    }
}

internal class NativeInstall(val directory: File?, val reason: String?) {
    companion object {
        fun at(directory: File): NativeInstall = NativeInstall(directory, null)
        fun absent(reason: String): NativeInstall = NativeInstall(null, reason)
    }
}
