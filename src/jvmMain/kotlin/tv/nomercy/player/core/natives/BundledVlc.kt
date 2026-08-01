// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File

// The bundled libVLC, which the loader looks at before anything else.
//
// It used to install itself: the previous binding found libVLC by asking every
// registered provider for candidate directories, and providers arrived through
// the JVM's service loader, so a class on the classpath was the whole
// registration. That mechanism went with the binding. The ordering it encoded
// did not — the loader asks HERE first and falls back to a system install only
// when this answers nothing, so the payload this library pinned and verified
// still wins, and a consumer still writes no line to get it.
//
// That ordering is the behavioural contract. The other way round means the
// version and the plugin set differ per user, and a bug reproduces on one
// machine out of five.
//
// Returning nothing is a normal answer. No payload for this platform yet, an
// offline machine with nothing staged: the loader moves on to the system
// install, exactly as it did before any of this existed.
internal object BundledVlc {

    // Where libvlc itself sits inside the payload, which is not the payload root
    // on every platform.
    //
    // A macOS payload keeps the shape of the VLC.app bundle it came out of: the
    // dylibs live in lib/ and the plugins are their sibling. Windows and Linux
    // keep both at the root. So the payload matches each platform's own
    // convention and this points one directory deeper on macOS.
    fun directory(): File? {
        val root: File = NativeRuntimes.directory(NativeRuntimeKind.LIB_VLC) ?: return null
        val nested = File(root, "lib")
        return if (nested.isDirectory) nested else root
    }
}
