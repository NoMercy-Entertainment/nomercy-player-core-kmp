// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package tv.nomercy.player.core.i18n

// Intl.DisplayNames is the browser's own ISO-639 table, the same role
// java.util.Locale plays on jvmMain/androidMain. Wrapped in a try so an
// unrecognised bcp47/locale pair (Intl throws RangeError rather than
// returning null) becomes the null this seam's contract expects.
@JsFun(
    """(bcp47, locale) => {
        try {
            const name = new Intl.DisplayNames([locale], { type: 'language' }).of(bcp47);
            return (name && name !== bcp47) ? name : null;
        } catch (e) {
            return null;
        }
    }""",
)
private external fun jsDisplayLanguage(bcp47: JsString, locale: JsString): JsString?

internal actual fun platformLanguageName(bcp47: String, locale: String): String? =
    jsDisplayLanguage(bcp47.toJsString(), locale.toJsString())?.toString()
