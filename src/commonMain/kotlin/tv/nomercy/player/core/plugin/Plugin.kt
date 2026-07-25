// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// A player plugin. Empty on purpose right now: it exists so addPlugin and
// getPlugin have a bound to name, and the plugin-runtime plan fills it in with
// the manifest, the lifecycle and the auto-cleaning surface a plugin actually
// gets. [O] is the plugin's options type.
public interface Plugin<O : Any>
