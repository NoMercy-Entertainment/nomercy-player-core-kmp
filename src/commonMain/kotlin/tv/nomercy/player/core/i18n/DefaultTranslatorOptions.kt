// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import tv.nomercy.player.core.ports.TranslationLoader

/**
 * How a [DefaultTranslator] was built.
 *
 * [fallbackLanguage] is nullable AND defaulted, and the difference carries
 * meaning: null means "do not fall back, show the key", which is what a
 * translator under test wants so a missing string is visible rather than
 * quietly English.
 */
public data class DefaultTranslatorOptions(
    val language: String? = null,
    val translations: Map<String, Map<String, String>> = emptyMap(),
    val loadTranslations: TranslationLoader? = null,
    /** What to show for a key nobody translated. Defaults to the key. */
    val onMissingTranslation: ((key: String, language: String) -> String)? = null,
    val fallbackLanguage: String? = "en",
)
