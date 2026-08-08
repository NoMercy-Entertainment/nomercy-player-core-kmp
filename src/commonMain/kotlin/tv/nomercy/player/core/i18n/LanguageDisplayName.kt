// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

/**
 * A language code, in the viewer's language.
 *
 * The reference composes this AT RENDER TIME and never stores it on the track,
 * for the reason its own comment gives: localisation depends on the UI language,
 * and a label frozen when the track was parsed is a label in whatever language
 * the parser felt like. The desktop menu showed exactly that — `Ondertiteling`
 * and `Uit` in Dutch, above rows reading `English (Full)` and `Croatian (Full)`.
 *
 * Mirrors `languageDisplayName` in
 * `packages/nomercy-video-player/src/plugins/desktop-ui/data/language-names.ts`.
 */

/**
 * Codes that appear in real containers and that no platform resolver knows.
 *
 * Standard ISO 639-2/B bibliographic codes — `fre`, `ger`, `chi` — resolve
 * natively everywhere, so they are deliberately absent here. These two do not,
 * and both come off discs and encoder output rather than out of a spec.
 */
internal val ISO3_QUIRKS: Map<String, String> = mapOf(
    "bra" to "pt-BR",
    "pob" to "pt-BR",
)

/**
 * Platform display-name lookup. Null when the runtime cannot resolve the code —
 * callers pick their own fallback, exactly as the reference does.
 */
internal expect fun platformLanguageName(bcp47: String, locale: String): String?

/**
 * The localised name for [code] rendered in [locale], or null.
 *
 * Returns null rather than the code itself, because every platform resolver
 * echoes an unrecognised tag back unchanged and a row reading `zzz` is a row a
 * viewer cannot choose. A caller that wants the raw code can ask for it; a
 * caller handed `zzz` cannot tell a resolved name from a failure.
 */
public fun languageDisplayName(code: String?, locale: String): String? {
    if (code.isNullOrBlank()) return null

    val bcp47: String = ISO3_QUIRKS[code.lowercase()] ?: code
    val name: String? = platformLanguageName(bcp47, locale)

    // A platform that answers with the tag itself has not named the language,
    // it has echoed the question — and "pt-BR" in a track menu is what the menu
    // was showing before any of this existed.
    return name?.takeIf { answer ->
        answer.isNotBlank() &&
            !answer.equals(bcp47, ignoreCase = true) &&
            !answer.equals(code, ignoreCase = true)
    }
}
