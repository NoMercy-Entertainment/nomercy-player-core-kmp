// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// The codes registration fails with, string-identical to the web kit.
//
// These are the strings a consumer switches on and a support ticket quotes, so
// they are contract. Eight live under core:plugin; use-plugin-after-dispose is
// deliberately not one of them — it is a lifecycle fault, not a plugin fault,
// and the web throws it under core:lifecycle.
public object PluginErrorCodes {
    public const val MISSING_DEP: String = "core:plugin/missing-dep"
    public const val DUPLICATE_ID: String = "core:plugin/duplicate-id"
    public const val VERSION_MISMATCH: String = "core:plugin/version-mismatch"
    public const val INCOMPATIBLE_CORE_VERSION: String = "core:plugin/incompatible-core-version"
    public const val DISPOSE_FAILED: String = "core:plugin/dispose-failed"
    public const val HAS_DEPENDENTS: String = "core:plugin/has-dependents"
    public const val INIT_TIMEOUT: String = "core:plugin/init-timeout"
    public const val STATE_UNINITIALIZED: String = "core:plugin/state-uninitialized"

    public const val USE_AFTER_DISPOSE: String = "core:lifecycle/use-plugin-after-dispose"
}
