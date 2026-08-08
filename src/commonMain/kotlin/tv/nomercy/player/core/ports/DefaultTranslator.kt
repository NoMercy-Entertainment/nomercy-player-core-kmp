// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * The kit's own translator.
 *
 * An interface rather than a shipped class, because what a default translator
 * DOES — read a map, fall back, report a miss — is the same everywhere while
 * where it reads FROM is not: a resource bundle on Android, a string catalogue
 * on Apple, a file on the desktop.
 *
 * [addTranslations] exists so a plugin can contribute its own strings after the
 * player is running. A plugin registered at run time otherwise has no way to
 * ship the labels for its own buttons.
 */
public interface DefaultTranslator {

    /** The string for [key], or whatever the miss behaviour returns. */
    public fun translate(key: String, replacements: Map<String, String> = emptyMap()): String

    public fun language(): String

    public suspend fun setLanguage(language: String)

    /** Adds or replaces strings for one language without a reload. */
    public fun addTranslations(language: String, strings: Map<String, String>)
}
