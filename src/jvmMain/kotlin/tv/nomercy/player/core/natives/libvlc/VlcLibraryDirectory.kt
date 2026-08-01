// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.Platform
import java.io.File
import tv.nomercy.player.core.natives.BundledVlc

// Which directory holds this machine's libVLC.
//
// Named directories, LOOKED AT, never searched. That distinction is the whole
// design of this file and it is written from a measurement: discovery that asks
// for candidate directories and walks each one RECURSIVELY took 118.6 seconds on
// a machine where VLC was installed and everything was warm, because one of the
// candidates was the JVM's working directory and the JVM had been started from
// the root of a large tree. Nothing here calls listFiles on anything but a
// candidate itself, and nothing here descends.
//
// The bundled payload comes first, always. It is the copy this project pinned
// and verified; a VLC the machine happens to have is the fallback underneath it,
// so a bug reproduces on the same binaries everywhere rather than on whichever
// version each user installed.
internal object VlcLibraryDirectory {

    // Null means no directory this library knows about holds libVLC, which is
    // not the end of the search: the caller then asks the operating system's own
    // loader, and on a Linux box with VLC from its package manager that is the
    // answer.
    fun find(): File? = candidates().firstOrNull(::holdsLibVlc)

    // Where the plugins are, given where the library is.
    //
    // Three shapes because there are three: the Windows and Linux payloads keep
    // plugins beside the library, a Linux distribution keeps them one directory
    // down under `vlc`, and a macOS VLC.app has lib/ and plugins/ as siblings.
    // Tried in that order and the first that exists wins.
    fun pluginsOf(library: File): File? = listOf(
        File(library, PLUGINS),
        File(library, "vlc/$PLUGINS"),
        File(library.parentFile, PLUGINS),
    ).firstOrNull { candidate -> candidate.isDirectory }

    private fun candidates(): List<File> = buildList {
        BundledVlc.directory()?.let(::add)
        addAll(installed())
    }

    // The default install location of each platform's own installer, and
    // nothing else. A VLC installed somewhere else is reachable by pointing
    // `nomercy.player.natives.libvlc.dir` at it, which is a supported answer
    // rather than a workaround — it is the same hook an air-gapped install and
    // a patched build use.
    private fun installed(): List<File> = when {
        Platform.isWindows() -> windowsInstalls()
        Platform.isMac() -> listOf(File("/Applications/VLC.app/Contents/MacOS/lib"))
        else -> linuxInstalls()
    }

    private fun windowsInstalls(): List<File> =
        listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")
            .mapNotNull(System::getenv)
            .distinct()
            .map { programFiles -> File(programFiles, "VideoLAN/VLC") }

    private fun linuxInstalls(): List<File> = listOf(
        "/usr/lib/${System.getProperty("os.arch")}-linux-gnu",
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib/aarch64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
        "/usr/local/lib",
    ).map(::File)

    // One listing of one directory. libVLC is versioned on Linux — libvlc.so.5
    // rather than libvlc.so — which is why this matches a pattern rather than a
    // name, and why the answer is the DIRECTORY: JNA finds the versioned file
    // itself once the directory is on its search path.
    private fun holdsLibVlc(directory: File): Boolean =
        directory.isDirectory && directory.list().orEmpty().any(LIBRARY_FILE::matches)

    private const val PLUGINS: String = "plugins"
    private val LIBRARY_FILE = Regex("""libvlc\.(dll|dylib)|libvlc\.so(\.\d+)*""")
}
