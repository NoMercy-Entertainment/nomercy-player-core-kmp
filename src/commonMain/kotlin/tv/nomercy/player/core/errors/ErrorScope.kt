// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// Which part of the system an error came from.
public enum class ScopeKind(public val token: String) {
    CORE("core"),
    BACKEND("backend"),
    STREAM("stream"),
    CUE("cue"),
    NETWORK("network"),
    AUTH("auth"),
    PLUGIN("plugin"),
}

// Where an error happened, and which instance of that thing. The web models
// this as a discriminated union; flattening it to kind plus an optional id
// keeps the same information without a sealed hierarchy a plugin could not
// extend.
//
// [id] names the plugin, backend or stream the error belongs to. It is what
// lets a host attribute a failure to one plugin instead of blaming the player.
public data class ErrorScope(val kind: ScopeKind, val id: String? = null) {
    public companion object {
        public fun core(): ErrorScope = ErrorScope(ScopeKind.CORE)
        public fun plugin(id: String): ErrorScope = ErrorScope(ScopeKind.PLUGIN, id)
        public fun backend(id: String): ErrorScope = ErrorScope(ScopeKind.BACKEND, id)
        public fun stream(id: String): ErrorScope = ErrorScope(ScopeKind.STREAM, id)
    }
}
