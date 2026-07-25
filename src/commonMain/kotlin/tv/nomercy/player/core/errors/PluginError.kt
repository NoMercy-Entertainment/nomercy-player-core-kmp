// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// A plugin operation failed: a missing dependency, a duplicate id, a version
// the core cannot satisfy, or a fault inside the plugin itself.
public open class PluginError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)

// Naming the plugin is what turns "the player broke" into "the lyrics plugin
// broke", so pluginId sets the scope rather than being buried in context.
public fun pluginError(
    code: String,
    message: String,
    severity: Severity = Severity.ERROR,
    pluginId: String? = null,
    context: Map<String, Any?> = emptyMap(),
): PluginError = PluginError(
    code = code,
    scope = if (pluginId != null) ErrorScope.plugin(pluginId) else ErrorScope.core(),
    severity = severity,
    message = "$code: $message",
    context = context,
)
