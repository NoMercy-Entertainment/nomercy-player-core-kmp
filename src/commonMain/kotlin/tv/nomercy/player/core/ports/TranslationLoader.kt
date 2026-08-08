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

