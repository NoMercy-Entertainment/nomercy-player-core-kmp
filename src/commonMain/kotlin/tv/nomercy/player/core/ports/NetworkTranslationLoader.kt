// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * A translation loader that fetches over the network.
 *
 * The same contract as [TranslationLoader] under its own name, because the web
 * exports both and a consumer wiring one reads the documentation for whichever
 * name it found. Null still means "nothing for this language" rather than an
 * error, so an untranslated language falls back instead of failing.
 */
public fun interface NetworkTranslationLoader {
    public suspend fun load(language: String): Map<String, String>?
}

/**
 * Where the network loader reads from.
 *
 * [parser] because a consumer's translation file is not always shaped like ours,
 * and [auth] because a private translation endpoint is an ordinary thing to
 * have.
 */
public data class NetworkTranslationLoaderOptions(
    val url: String,
    val auth: AuthConfig? = null,
    val parser: ((String) -> Map<String, String>)? = null,
)
