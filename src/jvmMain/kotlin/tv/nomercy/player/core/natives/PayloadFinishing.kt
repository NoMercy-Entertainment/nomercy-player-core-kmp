// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import java.util.concurrent.TimeUnit

private const val CACHE_GEN_TIMEOUT_SECONDS: Long = 120L

// The one thing a payload cannot carry, done on the machine that will load it.
internal fun interface PayloadFinishing {
    fun finish(directory: File)

    companion object {
        // Exhaustive over the kinds rather than a lookup by name: a new payload
        // will not compile until somebody has decided what finishing it needs,
        // which is the opposite of a map that silently answers "nothing".
        fun of(kind: NativeRuntimeKind): PayloadFinishing = when (kind) {
            NativeRuntimeKind.LIB_VLC -> VlcPluginCache
            // libmpv is one shared library with its dependencies statically
            // linked, so there is no plugin index to build and nothing to do
            // on arrival.
            NativeRuntimeKind.LIB_MPV -> None
            NativeRuntimeKind.LIB_ASS -> None
        }

        private val None = PayloadFinishing { }
    }
}

// libVLC's plugin index, regenerated only if the payload arrived without one.
//
// Normally it does not: the cache is generated when the payload is built,
// against the exact binaries in it, with every timestamp frozen first — libVLC
// records each plugin's size AND modification time and rechecks both, so a
// cache built against timestamps that do not survive packaging is a cache that
// goes stale on somebody else's machine. Freezing them is what makes a
// build-time cache survive the trip.
//
// This is the recovery path for when it did not survive anyway: a payload built
// on a host that could not run the generator, or an extraction that had to turn
// a symlink into a copy. Without it the first launch pays for indexing three
// hundred and sixty plugins, every launch, forever — measured at seven seconds
// on a machine whose installed VLC had exactly this problem.
private object VlcPluginCache : PayloadFinishing {

    override fun finish(directory: File) {
        val plugins = File(directory, "plugins")
        if (!plugins.isDirectory || File(plugins, "plugins.dat").isFile) return
        val generator: File = generatorIn(directory) ?: return
        runCatching {
            ProcessBuilder(generator.absolutePath, plugins.absolutePath)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(CACHE_GEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun generatorIn(directory: File): File? = listOf("vlc-cache-gen.exe", "vlc-cache-gen")
        .map { name -> File(directory, name) }
        .firstOrNull { candidate -> candidate.isFile }
}
