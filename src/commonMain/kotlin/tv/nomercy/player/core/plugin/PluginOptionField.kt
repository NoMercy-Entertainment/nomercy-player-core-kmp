// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// What a plugin's options look like, said out loud so a host can draw them.
//
// The web testbed reads a plugin's options object at runtime and generates a
// field per key, because JavaScript can enumerate a plain object. Kotlin cannot
// do that to a data class without reflection, and reflection is not something to
// put in the path of every consumer's build. So a plugin that wants its options
// editable declares them.
//
// Declared rather than inferred is also the honest shape for the thing the
// editor needs and a type does not carry: a label a person can read, the range a
// number is allowed to take, and the choices behind a string.
//
// A plugin that declares nothing is not broken — it has no options worth
// editing, and the host draws its row with a toggle and no block.
public sealed class PluginOptionField {

    /** Stable within one plugin. What a host stores a changed value against. */
    public abstract val key: String

    /** Shown to a person, so it is prose rather than a camelCase key. */
    public abstract val label: String

    public class Toggle(
        override val key: String,
        override val label: String,
        public val value: Boolean,
        public val apply: (Boolean) -> Unit,
    ) : PluginOptionField()

    // Doubles rather than a numeric hierarchy: every editor renders one control
    // for a number, and an Int option that arrives as 2.0 is a rounding the
    // plugin already has to do at its own boundary.
    public class Number(
        override val key: String,
        override val label: String,
        public val value: Double,
        public val min: Double,
        public val max: Double,
        public val step: Double = 1.0,
        public val apply: (Double) -> Unit,
    ) : PluginOptionField()

    public class Choice(
        override val key: String,
        override val label: String,
        public val value: String,
        public val choices: List<String>,
        public val apply: (String) -> Unit,
    ) : PluginOptionField()

    public class Text(
        override val key: String,
        override val label: String,
        public val value: String,
        public val apply: (String) -> Unit,
    ) : PluginOptionField()
}
