// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlin.jvm.JvmInline

/**
 * A dependency, and how badly it is needed.
 *
 * [Detailed.optional] is the difference between a plugin that will not load and
 * one that loses a feature: a spectrum without an audio graph is broken, a media
 * session without a cast sender is simply local. Treating every requirement as
 * hard makes a consumer install plugins they do not want; treating none as hard
 * turns the first missing dependency into a null somewhere inside a render loop.
 *
 * [Detailed.minVersion] because a dependency present at the wrong version fails
 * later and more confusingly than one that is absent — usually at the single
 * call site the newer version added.
 */
public sealed interface RequireSpec {

    public val plugin: PluginCtorWithId

    /** Must be present. */
    @JvmInline
    public value class Required(public override val plugin: PluginCtorWithId) : RequireSpec

    public data class Detailed(
        public override val plugin: PluginCtorWithId,
        public val optional: Boolean = false,
        public val minVersion: String? = null,
    ) : RequireSpec
}
