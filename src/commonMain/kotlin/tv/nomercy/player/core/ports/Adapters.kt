// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * The bus the player publishes on and a host can listen to.
 *
 * [off] with no handler drops every listener for that event, which is what a
 * teardown needs — a chrome unmounting cannot hold references to the closures it
 * registered, and without this each remount leaves the previous one subscribed.
 */
public interface EventBus {

    public fun on(event: String, handler: (Any?) -> Unit)

    /** Fires at most once, then removes itself whether or not anyone unsubscribes. */
    public fun once(event: String, handler: (Any?) -> Unit)

    /** One handler, or every handler for that event when null. */
    public fun off(event: String, handler: ((Any?) -> Unit)? = null)

    /** Everything, for every event. The teardown case. */
    public fun offAll()

    public fun emit(event: String, data: Any? = null)
}

/**
 * Which language tags to try, best first, for one requested tag.
 *
 * A list rather than one answer, because `nl-BE` should fall back to `nl` and
 * then to whatever the player was built with — and a matcher returning a single
 * string forces every caller to write that ladder again, differently.
 */
public fun interface LanguageMatcher {
    public fun candidatesFor(tag: String): List<String>
}

/**
 * Turns subtitle markup into whatever this toolkit draws.
 *
 * The web returns a DOM fragment. There is no fragment here, so a renderer is
 * given the markup and hands back its own drawable — which is the seam that lets
 * Compose, SwiftUI and libass each render the same cue their own way.
 */
public interface SubtitleRenderer<R> {
    public fun render(markup: String): R
}

/**
 * Whether the display satisfies a media query.
 *
 * Narrow on purpose: the quality ladder asks about dynamic range and colour
 * gamut, and a port handing over the whole screen API would let the ladder
 * depend on a dozen more things nobody can reproduce in a test.
 */
public fun interface DisplayRangeProbe {
    public fun matches(query: String): Boolean
}

/**
 * Where a language's strings come from.
 *
 * Null means "no strings for this language", NOT an error: a player asked for a
 * language nobody has translated falls back, and a loader that threw would make
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

/**
 * A translation loader that fetches.
 *
 * [parser] because a consumer's translation file is not always the shape ours
 * is, and [auth] because a private translation endpoint is a normal thing to
 * have.
 */
public data class NetworkTranslationLoaderOptions(
    val url: String,
    val auth: AuthConfig? = null,
    val parser: ((String) -> Map<String, String>)? = null,
)

/**
 * Replacing a player method at run time, and putting it back.
 *
 * Every override returns its own undo rather than a global reset: two consumers
 * overriding two methods must be able to unwind independently, and a single
 * `restoreAll` would have the second one's teardown silently revert the first.
 *
 * [overrides] names WHO overrode each method, because "why is seek behaving
 * strangely" is answerable in one call when the answer carries a name and
 * unanswerable when it does not.
 */
public interface PlayerExperimental {

    /** Replaces [method]; the returned function puts the original back. */
    public fun override(method: String, fn: (List<Any?>) -> Any?): () -> Unit

    public fun restore(method: String)

    public fun overrides(): List<MethodOverride>
}

public data class MethodOverride(
    val method: String,
    /** The plugin id that installed it, or `consumer`. */
    val by: String,
) {
    public companion object {
        public const val CONSUMER: String = "consumer"
    }
}
