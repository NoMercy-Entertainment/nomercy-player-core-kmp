// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.ports

// The browser's own ISO-639 table via Intl.DisplayNames, read against the
// page's own language rather than a caller-supplied locale — this port has no
// locale parameter, unlike i18n.platformLanguageName. Same fallback shape as
// jvmMain/androidMain: try the tag as given, then its ISO 639-2/B folding.
@JsFun(
    """(tag) => {
        try {
            const name = new Intl.DisplayNames([navigator.language], { type: 'language' }).of(tag);
            return (name && name !== tag) ? name : null;
        } catch (e) {
            return null;
        }
    }""",
)
private external fun jsDisplayLanguage(tag: JsString): JsString?

public actual fun displayLanguage(tag: String): String {
    if (tag.isBlank()) return tag

    return named(tag) ?: named(threeToTwo(tag.substringBefore('-'))) ?: tag
}

private fun named(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    return jsDisplayLanguage(tag.toJsString())?.toString()
}

// Same bibliographic table as jvmMain/androidMain — the browser has no API to
// enumerate ISO 639-2/B codes to build this from, so it is duplicated rather
// than derived, exactly as jvmMain's own doc comment already accepts for the
// Android/JVM split.
private val THREE_TO_TWO: Map<String, String> = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
    "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
    "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
    "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)

private fun threeToTwo(code: String): String? =
    code.takeIf { it.length == 3 }?.lowercase()?.let { THREE_TO_TWO[it] }
