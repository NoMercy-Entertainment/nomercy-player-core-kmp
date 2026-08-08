// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import tv.nomercy.player.core.ports.LanguageMatcher
import tv.nomercy.player.core.ports.Translations
import tv.nomercy.player.core.ports.Translator

/**
 * The translator the kit ships, so a consumer gets one without writing it.
 *
 * [Translator] is a port, and until this existed the only implementations of it
 * in the whole trio were two test doubles: every consumer either wrote a
 * translator or ran with `translator = null` and saw raw keys in the chrome.
 * The web has shipped a concrete one from the start, and this is that class —
 * an in-memory bundle table read through a BCP-47 fallback chain.
 *
 * A key resolves in this order, which is the web's:
 *
 *   1. the bundles along the fallback chain, best first
 *   2. [DefaultTranslatorOptions.onMissingTranslation]
 *   3. the key itself
 *
 * Never an exception, and never an empty string. A missing translation must
 * leave something readable on screen — a button labelled with its own key is
 * a bug report somebody can act on, and a blank button is not.
 */
public class DefaultTranslator(
    private val options: DefaultTranslatorOptions = DefaultTranslatorOptions(),
    private val matcher: LanguageMatcher = Bcp47FallbackChain,
) : Translator {

    private val bundles: MutableMap<String, MutableMap<String, String>> =
        options.translations
            .mapValues { (_, strings) -> strings.toMutableMap() }
            .toMutableMap()

    /**
     * Tags the loader has already been asked for.
     *
     * Marked BEFORE the loader is awaited, so a re-entrant `language()` — which
     * a chrome does when it rebuilds on a language change — cannot fire the same
     * fetch twice.
     */
    private val loaded: MutableSet<String> = bundles.keys.toMutableSet()

    private var currentLanguage: String = options.language ?: DEFAULT_LANGUAGE

    override fun t(key: String, vars: Map<String, String>): String {
        val chain: List<String> = matcher.candidatesFor(currentLanguage).toMutableList().apply {
            val fallback: String? = options.fallbackLanguage
            if (fallback != null && fallback !in this) add(fallback)
        }

        val raw: String? = chain.firstNotNullOfOrNull { tag -> bundles[tag]?.get(key) }
        val resolved: String = raw
            ?: options.onMissingTranslation?.invoke(key, currentLanguage)
            ?: key

        return if (vars.isEmpty()) resolved else interpolate(resolved, vars)
    }

    override fun language(): String = currentLanguage

    override suspend fun language(lang: String) {
        currentLanguage = lang

        // The whole chain, not just the tag asked for: a regional variant
        // (pt-BR) needs its parent (pt) in memory as the natural fallback for
        // the keys the regional file does not override.
        for (tag in matcher.candidatesFor(lang)) {
            // add() answers whether it was new, which is the mark and the
            // question in one — and it happens BEFORE the loader is awaited.
            if (loaded.add(tag)) fetchInto(tag)
        }
    }

    private suspend fun fetchInto(tag: String) {
        val bundle: MutableMap<String, String> = bundles.getOrPut(tag) { mutableMapOf() }
        val loader = options.loadTranslations ?: return

        // A loader that throws must not break the language switch — the player
        // falls through to whatever is already loaded. Losing a translation is
        // a cosmetic failure; an exception here would take down the surface
        // that was mid-rebuild.
        val strings: Map<String, String>? = runCatching { loader.load(tag) }.getOrNull()
        bundle.putAll(strings.orEmpty())
    }

    override fun addTranslations(bundle: Translations) {
        for ((language, strings) in bundle) {
            bundles.getOrPut(language) { mutableMapOf() }.putAll(strings)
        }
        // Deliberately NOT marked in `loaded`: merging a plugin's static bundle
        // at registration must not satisfy the loader's contract, or switching
        // to that language afterwards would skip the fetch that carries the
        // rest of the strings.
    }

    override fun translation(lang: String, key: String): String? = bundles[lang]?.get(key)

    override fun translation(lang: String, key: String, value: String) {
        if (lang !in bundles) loaded.add(lang)
        bundles.getOrPut(lang) { mutableMapOf() }[key] = value
    }

    override fun removeTranslations(prefix: String, lang: String?) {
        val languages: List<String> = lang?.let(::listOf) ?: bundles.keys.toList()
        for (language in languages) {
            bundles[language]?.keys?.removeAll { key -> key.startsWith(prefix) }
        }
    }

    override fun dispose() {
        bundles.clear()
        loaded.clear()
    }

    // `{name}` replaced from vars; an unknown name is left as it was written
    // rather than blanked, so the gap names the variable that was not supplied.
    private fun interpolate(text: String, vars: Map<String, String>): String =
        VARIABLE.replace(text) { match -> vars[match.groupValues[1]] ?: match.value }

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
        val VARIABLE = Regex("""\{(\w+)}""")
    }
}
