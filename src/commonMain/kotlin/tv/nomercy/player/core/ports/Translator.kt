// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Language code to key to translated string. A plugin ships its own bundle in
// its manifest and its keys are namespaced plugin.<id>.<key>, so shipping
// translations is part of writing a plugin rather than bolted on afterwards.
public typealias Translations = Map<String, Map<String, String>>

// Turns a key into a sentence in the viewer's language.
//
// A port, not an implementation, so a consumer already running i18next or
// FormatJS keeps one translation system instead of two. The default lookup and
// the bundled catalogues ship with the i18n scaffold.
//
// A missing key returns the key rather than an empty string or a throw: a
// visible "player.play" in the UI is a bug report, and a blank button is not.
public interface Translator {
    public fun t(key: String, vars: Map<String, String> = emptyMap()): String

    public fun language(): String

    // Suspend because switching language may load a catalogue.
    public suspend fun language(lang: String)

    public fun addTranslations(bundle: Translations)

    public fun translation(lang: String, key: String): String?
    public fun translation(lang: String, key: String, value: String)

    // Removes by key prefix, which is how a plugin's strings leave with it.
    public fun removeTranslations(prefix: String, lang: String? = null)

    public fun dispose()
}
