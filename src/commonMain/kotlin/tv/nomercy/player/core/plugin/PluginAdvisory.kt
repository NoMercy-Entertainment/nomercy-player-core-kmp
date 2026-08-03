// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.player.PlayerPhase

// "Calling this, here, is risky" — as data rather than as a handler.
//
// This is how a plugin warns about a call it cares about without core knowing
// what any plugin cares about. A cast plugin says seeking during a handoff is
// an error; a preferences plugin says writing subtitle style mid-load will be
// overwritten. Neither needs code at the call site, and core needs no list of
// which plugins object to what.
//
// Pure data on purpose. A plugin wanting to mutate the arguments or veto the
// action outright subscribes to CoreEvents.BeforeMutation directly; this is the
// declarative half, for the common case that is only ever a message.
public data class PluginAdvisory(
    // The mutating method this watches, as the guard names it: "time",
    // "subtitleStyle", "quality".
    val method: String,

    // Empty matches any phase, which is the reference's optional field said in
    // a type that has no optional.
    val duringPhase: Set<PlayerPhase> = emptySet(),

    // Event names whose dispatch must be in flight. This is what makes an
    // advisory able to say "inside a beforePlay handler" rather than merely
    // "while playing", and it works for a plugin's own namespaced events too,
    // because the dispatch stack carries whatever name was dispatched.
    val duringEvent: Set<String> = emptySet(),

    val severity: Severity = Severity.WARNING,

    // The code suffix. The full code is plugin:<plugin-id>/<reason>, built by
    // the registry, so an advisory cannot claim to come from a plugin other
    // than the one that declared it.
    val reason: String,

    val message: String,
)

// One advisory that matched, with the plugin it came from already resolved.
//
// A separate type so core can raise it without holding the plugin: the registry
// knows which plugin declared what and stamps the code, and the context only
// has to emit what it is handed. That is also what stops an advisory claiming
// to come from a plugin other than the one that declared it.
public data class PluginAdvisoryNotice(
    val pluginId: String,
    val method: String,
    val code: String,
    val message: String,
    val severity: Severity,
)

// Whether this advisory matches the call that just happened.
//
// Both conditions are AND, and an empty set means "any" rather than "none".
// Getting that backwards would make the most common advisory — a method, no
// phase, no event — match nothing at all, which is indistinguishable from the
// system not being wired.
public fun PluginAdvisory.matches(method: String, phase: PlayerPhase, dispatchStack: List<String>): Boolean {
    if (this.method != method) return false
    if (duringPhase.isNotEmpty() && phase !in duringPhase) return false

    return duringEvent.isEmpty() || dispatchStack.any { it in duringEvent }
}
