// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.Locale

// forLanguageTag rather than the Locale(String) constructor, because the tags in
// a manifest are BCP 47 — "pt-BR" is a different dub from "pt" and the
// deprecated constructor drops the region.
//
// The default locale is read per call rather than captured. Android delivers a
// locale change as a configuration change without restarting the process, so a
// captured one would leave every menu in the previous language until the app
// was killed.
public actual fun displayLanguage(tag: String): String {
    if (tag.isBlank()) return tag

    val display: Locale = Locale.getDefault()
    return named(tag, display) ?: named(twoLetterFor(tag.substringBefore('-')), display) ?: tag
}

// Null when the platform has no name for this tag, so the caller can try the
// other spelling before giving up. getDisplayLanguage answers with the tag it
// was handed in that case, which is indistinguishable from success until it is
// compared back.
private fun named(tag: String?, display: Locale): String? {
    if (tag.isNullOrBlank()) return null

    val name: String = Locale.forLanguageTag(tag).getDisplayLanguage(display)
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
}

// Built from the platform's own ISO tables rather than written out.
//
// This is the whole point of the change: the app carried about a hundred
// hand-written pairs, which was English-only and missing whatever language a
// title shipped in next. Java already knows every one of them and keeps the
// list current with the OS.
private val THREE_TO_TWO: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale.forLanguageTag(code)
            // The terminology code — "nld" for Dutch. Bibliographic spellings
            // such as "dut" are handled below.
            runCatching { locale.isO3Language }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put(it.lowercase(), code) }
        }
        // The handful of languages ISO 639-2 gives two codes, where the
        // bibliographic one is what older tools write. Java's tables only carry
        // the terminology side, so these are the exceptions the platform cannot
        // answer rather than a list of languages.
        putAll(BIBLIOGRAPHIC)
    }
}

private val BIBLIOGRAPHIC: Map<String, String> = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
    "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
    "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
    "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)

private fun twoLetterFor(code: String): String? =
    code.takeIf { it.length == THREE }?.lowercase()?.let { THREE_TO_TWO[it] }

private const val THREE = 3
