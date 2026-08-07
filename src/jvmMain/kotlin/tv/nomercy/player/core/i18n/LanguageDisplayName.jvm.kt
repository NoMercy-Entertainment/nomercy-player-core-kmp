// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import java.util.Locale

// Three-letter codes have to be folded to two before the lookup.
//
// Containers carry ISO 639-2 — `eng`, `fre`, `ger`, `nld` — and the JVM's locale
// data is keyed on ISO 639-1. `Locale.forLanguageTag("eng").getDisplayLanguage(dutch)`
// finds no entry for "eng", falls back, and returns "English" INSIDE A DUTCH
// MENU: a wrong answer that looks like a working one, which is why the first
// test written against it passed for `en` and failed for `eng`.
//
// Built from the JDK's own table rather than hand-written, so it covers every
// code the platform knows and cannot drift from it. Both bibliographic and
// terminological forms land here — `getISO3Language` answers `ger` for German
// on some JDKs and `deu` on others, and the map takes whichever it gives.
private val ISO3_TO_ISO1: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale.forLanguageTag(code)
            runCatching { locale.isO3Language }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put(it.lowercase(), code) }
        }
        // The bibliographic codes the JDK's terminological table misses. ISO
        // 639-2/B is what a muxer writes; 639-2/T is what getISO3Language tends
        // to return, and the two disagree on exactly these.
        putAll(
            mapOf(
                "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh",
                "cze" to "cs", "gre" to "el", "ice" to "is", "mac" to "mk",
                "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
                "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
                "geo" to "ka", "tib" to "bo", "wel" to "cy",
            ),
        )
    }
}

internal actual fun platformLanguageName(bcp47: String, locale: String): String? {
    val primary: String = bcp47.substringBefore('-').lowercase()
    val folded: String = ISO3_TO_ISO1[primary]?.let { bcp47.replaceFirst(primary, it, ignoreCase = true) } ?: bcp47

    return Locale.forLanguageTag(folded)
        .getDisplayLanguage(Locale.forLanguageTag(locale))
        .takeIf { it.isNotBlank() }
}
