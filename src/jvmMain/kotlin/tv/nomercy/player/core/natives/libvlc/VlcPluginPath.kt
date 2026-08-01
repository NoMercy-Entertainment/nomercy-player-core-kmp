// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import java.io.File
import java.lang.reflect.Method

// Telling libVLC where its plugins are, which it will only be told one way.
//
// VLC 3 reads the plugin directory from the VLC_PLUGIN_PATH environment
// variable and from nowhere else — there is no libvlc_new argument for it and
// no API call. So the variable has to exist in the NATIVE process environment
// before libvlc_new runs, and the JVM cannot put it there: System.getenv is a
// snapshot taken at startup and setting a system property changes nothing a C
// library reads.
//
// Hence the C runtime, directly. On POSIX that is setenv; on Windows it is
// msvcrt's _putenv, because the official VLC build links msvcrt and reads its
// copy of the environment — SetEnvironmentVariable would update the process
// block that this particular getenv does not consult.
internal object VlcPluginPath {

    // Left alone when the machine already set it. Somebody who exported
    // VLC_PLUGIN_PATH has said which plugins they want used, and a library that
    // overwrote that would be answering a question it was not asked.
    fun export(libraryDirectory: File) {
        if (!System.getenv(VARIABLE).isNullOrEmpty()) return
        val plugins: File = VlcLibraryDirectory.pluginsOf(libraryDirectory) ?: return
        runCatching { assign(plugins.absolutePath) }
    }

    private fun assign(path: String) {
        if (Platform.isWindows()) {
            WindowsCRuntime.INSTANCE.putenv("$VARIABLE=$path")
        } else {
            PosixCRuntime.INSTANCE.setenv(VARIABLE, path, OVERWRITE)
        }
    }

    private const val VARIABLE: String = "VLC_PLUGIN_PATH"
    private const val OVERWRITE: Int = 1
}

// msvcrt's _putenv. The underscore is Microsoft's, and the mapper below is what
// keeps it out of the Kotlin name.
internal interface WindowsCRuntime : Library {

    fun putenv(assignment: String): Int

    companion object {
        val INSTANCE: WindowsCRuntime = Native.load(
            "msvcrt",
            WindowsCRuntime::class.java,
            mapOf(Library.OPTION_FUNCTION_MAPPER to UnderscorePrefixed),
        )
    }
}

private object UnderscorePrefixed : FunctionMapper {
    override fun getFunctionName(library: NativeLibrary, method: Method): String = "_" + method.name
}

internal interface PosixCRuntime : Library {

    fun setenv(name: String, value: String, overwrite: Int): Int

    companion object {
        val INSTANCE: PosixCRuntime = Native.load("c", PosixCRuntime::class.java)
    }
}
