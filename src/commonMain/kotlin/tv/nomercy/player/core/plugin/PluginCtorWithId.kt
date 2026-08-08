// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlin.reflect.KClass

/**
 * A plugin class, identified by its own static id.
 *
 * The id lives on the CLASS rather than on an instance, which is what lets the
 * player answer "is the equaliser registered" before constructing one, and lets
 * a dependency be declared by naming a class instead of repeating a string that
 * nothing checks.
 *
 * [replaces] is how a consumer swaps our implementation for theirs without both
 * of them running: two plugins listening to the same events and both writing to
 * the same state is a fight nobody can debug from the outside.
 */
public data class PluginCtorWithId(
    public val type: KClass<*>,
    public val id: String,
    public val version: String? = null,
    public val description: String? = null,
    public val minCoreVersion: String? = null,
    public val requires: List<RequireSpec> = emptyList(),
    /** The id of a plugin this one takes the place of. */
    public val replaces: String? = null,
    /** Higher goes first. Otherwise registration follows declaration order. */
    public val priority: Int? = null,
)
