// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import tv.nomercy.player.core.ports.LanguageMatcher

/**
 * A language tag walked down its parent chain.
 *
 *     pt-BR       -> [pt-BR, pt]
 *     zh-Hant-TW  -> [zh-Hant-TW, zh-Hant, zh]
 *     en          -> [en]
 *
 * The default [LanguageMatcher], and what [DefaultTranslator] looks a key up
 * through. One trailing subtag at a time on the `-` separator.
 *
 * Case and whitespace are preserved deliberately: bundle keys are matched
 * byte for byte, and a matcher that lower-cased `zh-Hant` would stop finding
 * the bundle it was handed.
 */
public object Bcp47FallbackChain : LanguageMatcher {

    override fun candidatesFor(tag: String): List<String> {
        if (tag.isEmpty()) return emptyList()

        val chain: MutableList<String> = mutableListOf(tag)
        var current: String = tag
        while (current.contains(SEPARATOR)) {
            current = current.substringBeforeLast(SEPARATOR)
            chain += current
        }

        return chain
    }

    private const val SEPARATOR = '-'
}
