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
 * A plugin to register, with or without options.
 *
 * Two shapes because most registrations carry no options, and forcing an empty
 * object on every one of them is noise a consumer reads on every line of their
 * setup. The web has the same pair for the same reason.
 */
public sealed interface PluginSpec {

    public val plugin: PluginCtorWithId

    @JvmInline
    public value class Bare(public override val plugin: PluginCtorWithId) : PluginSpec

    public data class WithOptions(
        public override val plugin: PluginCtorWithId,
        public val opts: Any?,
    ) : PluginSpec
}
