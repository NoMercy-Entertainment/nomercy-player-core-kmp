// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.Locale

// The same lookup as Android's, duplicated because the two targets share no
// source set that can see java.util.Locale — Apple sits between them in the
// default hierarchy, and inventing a jvmAndAndroid set to hold one delegation
// costs more than the line saves.
public actual fun displayLanguage(tag: String): String {
    if (tag.isBlank()) return tag

    val display: Locale = Locale.getDefault()
    return named(tag, display) ?: named(threeToTwo(tag.substringBefore('-')), display) ?: tag
}

// Null when the platform has no name for this tag, so the caller can try the
// other spelling before giving up.
private fun named(tag: String?, display: Locale): String? {
    if (tag.isNullOrBlank()) return null

    val name: String = Locale.forLanguageTag(tag).getDisplayLanguage(display)
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
}

// Built from the platform's ISO tables rather than written out, so a language
// added to the OS is named without anyone editing this file.
private val THREE_TO_TWO: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            runCatching { Locale.forLanguageTag(code).isO3Language }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put(it.lowercase(), code) }
        }
        putAll(BIBLIOGRAPHIC)
    }
}

// The languages ISO 639-2 gives two codes, where the bibliographic spelling is
// what older tools write. Java's tables carry only the terminology side.
private val BIBLIOGRAPHIC: Map<String, String> = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
    "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
    "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
    "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)

private fun threeToTwo(code: String): String? =
    code.takeIf { it.length == THREE }?.lowercase()?.let { THREE_TO_TWO[it] }

private const val THREE = 3
