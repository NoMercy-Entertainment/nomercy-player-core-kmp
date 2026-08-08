// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * Cast settings that belong to the player's own configuration.
 *
 * Separate from [CastSenderOptions] because these are decided by whoever builds
 * the player and those are decided by whoever registers the plugin — and on
 * every client so far those are two different people.
 */
public data class CastConfig(
    /** Load the receiver framework at setup rather than on first use. */
    val autoLoad: Boolean = false,

    val receiverApplicationId: String? = null,

    val autoJoinPolicy: CastAutoJoinPolicy? = null,

    /** Rejoin a session left running from a previous visit. */
    val resumeSavedSession: Boolean = false,

    /** Where the receiver framework is loaded from, when it is loaded at all. */
    val scriptUrl: String? = null,

    val loadTimeoutMs: Long? = null,
)

/**
 * How a session joins one that is already running.
 *
 * `origin-scoped` rejoins a session this origin started; `page-scoped` never
 * rejoins. The difference is whether opening a second tab takes over the
 * television somebody else in the house is watching.
 */
public enum class CastAutoJoinPolicy(public val id: String) {
    ORIGIN_SCOPED("origin-scoped"),
    TAB_AND_ORIGIN_SCOPED("tab-and-origin-scoped"),
    PAGE_SCOPED("page-scoped"),
}
