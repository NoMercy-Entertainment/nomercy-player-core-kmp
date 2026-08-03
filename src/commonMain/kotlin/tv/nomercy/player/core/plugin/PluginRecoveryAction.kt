// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// What a plugin does about one of its own errors.
//
// Declared per error code, so the decision is data an author writes once rather
// than a catch block at every call site. The reference applies it the moment
// the error is surfaced; without it a native plugin that fails keeps failing
// the same way on every event that reaches it, where the web one would have
// turned itself off after the first.
public enum class PluginRecoveryAction(public val token: String) {
    // Already surfaced. This exists so "we looked at this and it is fine" is
    // something an author can write down, rather than being indistinguishable
    // from having never considered it.
    IGNORE("ignore"),

    // Stop reacting, keep the state. A plugin that cannot reach a service it
    // needs is more useful switched off than throwing on every tick.
    DISABLE("disable"),

    // Try the thing once more. The plugin supplies the body by overriding
    // [Plugin.retryLastOperation]; without one this is a logged no-op rather
    // than a silent one, because a declared recovery that cannot run is a
    // configuration mistake worth seeing.
    RETRY_ONCE("retry-once"),

    // Do the lesser thing instead, through [Plugin.activateFallback].
    FALLBACK("fallback");

    public companion object {
        public fun fromToken(token: String): PluginRecoveryAction? =
            entries.firstOrNull { it.token == token }
    }
}
