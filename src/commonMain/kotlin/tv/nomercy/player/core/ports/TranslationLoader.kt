// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Where a language's strings come from.
 *
 * Null means "no strings for this language", NOT an error. A player asked for a
 * language nobody has translated falls back; a loader that threw would make
 * every untranslated language a crash instead of English.
 */
public fun interface TranslationLoader {
    public suspend fun load(language: String): Map<String, String>?
}

/**
 * How the default translator was built.
 *
 * [fallbackLanguage] is nullable AND defaulted, and the difference matters: null
 * means "do not fall back, show the key", which is what a translator under test
 * wants so a missing string is visible rather than quietly English.
 */
public data class DefaultTranslatorOptions(
    val language: String? = null,
    val translations: Map<String, Map<String, String>> = emptyMap(),
    val loadTranslations: TranslationLoader? = null,
    /** What to show for a key nobody translated. Defaults to the key. */
    val onMissingTranslation: ((key: String, language: String) -> String)? = null,
    val fallbackLanguage: String? = "en",
)
