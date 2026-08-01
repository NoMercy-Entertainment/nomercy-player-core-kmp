// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import java.io.File

// The seven halves of libVLC this library speaks to, bound to one shared object.
//
// Seven proxies rather than one because libVLC's C API is a single flat
// namespace of about a hundred functions and reading it that way is a wall.
// They cost nothing: JNA opens the library once and every proxy resolves out of
// the same handle.
internal class LibVlcBinding(library: String) {

    val core: LibVlcCoreApi = bind(library, LibVlcCoreApi::class.java)
    val media: LibVlcMediaApi = bind(library, LibVlcMediaApi::class.java)
    val player: LibVlcPlayerApi = bind(library, LibVlcPlayerApi::class.java)
    val status: LibVlcStatusApi = bind(library, LibVlcStatusApi::class.java)
    val audio: LibVlcAudioApi = bind(library, LibVlcAudioApi::class.java)
    val video: LibVlcVideoApi = bind(library, LibVlcVideoApi::class.java)
    val events: LibVlcEventApi = bind(library, LibVlcEventApi::class.java)

    private companion object {
        fun <T : Library> bind(library: String, api: Class<T>): T =
            Native.load(library, api, LibVlcNames.options)
    }
}

// Finding libVLC and binding to it, once per process.
//
// libVLC is loaded as a shared library through its published C API. That is the
// arrangement libVLC's own licence — LGPL-2.1-or-later — is written for, and it
// is why this Apache-2.0 library can ship a payload: the engine stays a separate
// work that the user may replace, which they can do by pointing
// `nomercy.player.natives.libvlc.dir` at their own build.
internal object LibVlcLoader {

    // Throws what actually went wrong rather than a wrapper. A machine with no
    // libVLC produces UnsatisfiedLinkError from JNA, and a machine that has the
    // library but not its plugins produces an IllegalStateException from
    // libvlc_new — two different problems that need two different answers, and
    // flattening them into one message is how "install VLC" gets told to
    // somebody who already has.
    fun require(): LibVlcBinding = bound.getOrThrow()

    // Memoized because the answer cannot change while the process runs, and
    // because the search — a handful of directory listings — is paid for once.
    private val bound: Result<LibVlcBinding> by lazy { runCatching(::load) }

    private fun load(): LibVlcBinding {
        VlcLibraryDirectory.find()?.let(::point)
        return LibVlcBinding(LIBRARY)
    }

    // libvlccore is named as well as libvlc, because libvlc cannot resolve
    // without it and Windows looks for a dependency beside the DLL that needs it
    // only when that DLL was opened by absolute path — which is what the search
    // path arranges.
    private fun point(directory: File) {
        NativeLibrary.addSearchPath(CORE_LIBRARY, directory.absolutePath)
        NativeLibrary.addSearchPath(LIBRARY, directory.absolutePath)
        VlcPluginPath.export(directory)
    }

    private val LIBRARY: String = if (Platform.isWindows()) "libvlc" else "vlc"
    private val CORE_LIBRARY: String = if (Platform.isWindows()) "libvlccore" else "vlccore"
}
