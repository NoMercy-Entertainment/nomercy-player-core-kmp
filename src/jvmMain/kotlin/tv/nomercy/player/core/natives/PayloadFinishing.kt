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
            // Neither payload needs anything done on arrival: libmpv is one
            // shared library beside its dependencies and libass is one too.
            // libVLC needed a plugin index rebuilt and took this file with it.
            NativeRuntimeKind.LIB_MPV -> None
            NativeRuntimeKind.LIB_ASS -> None
        }

        private val None = PayloadFinishing { }
    }
}

