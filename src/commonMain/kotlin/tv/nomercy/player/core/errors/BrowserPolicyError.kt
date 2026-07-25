// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The platform refused the action on policy grounds — autoplay without a
// gesture, a permission not granted. Code: core:policy/<reason>.
//
// The name says Browser and this is a native library. It is kept because the
// code identity is shared across all three ecosystems: a support ticket
// quoting core:policy/autoplay-blocked has to mean one thing whether it came
// from web, Android or iOS. Renaming it here would split that.
//
// [suggestion] is the field that makes this class worth having: a policy
// refusal is the one failure the viewer can fix themselves, so it carries the
// sentence to show them.
public open class BrowserPolicyError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
    suggestion: String? = null,
) : PlayerError(code, scope, severity, message, cause, context, suggestion)

public fun browserPolicyError(
    code: String,
    message: String,
    suggestion: String? = null,
    context: Map<String, Any?> = emptyMap(),
): BrowserPolicyError = BrowserPolicyError(
    code = code,
    scope = ErrorScope.core(),
    message = "$code: $message",
    context = context,
    suggestion = suggestion,
)
