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
import com.sun.jna.NativeLibrary
import java.lang.reflect.Method

// libVLC's C names, derived rather than repeated.
//
// Every entry point in this package is `libvlc_` followed by lower_snake_case,
// without exception, so the Kotlin name can be the same word in the shape Kotlin
// reads in — and the one mechanical rule below turns one into the other. The
// alternative is forty declarations carrying a string that duplicates the method
// beside it, where a typo is a link error at the first call rather than a
// compile error.
internal object LibVlcNames : FunctionMapper {

    override fun getFunctionName(library: NativeLibrary, method: Method): String =
        PREFIX + method.name.replace(CAPITAL) { match -> "_" + match.value.lowercase() }

    // Every interface in this package is loaded through the same rule, so the
    // options map is built once here rather than at each load site.
    val options: Map<String, Any> = mapOf(Library.OPTION_FUNCTION_MAPPER to this)

    private const val PREFIX: String = "libvlc_"
    private val CAPITAL = Regex("[A-Z]")
}
