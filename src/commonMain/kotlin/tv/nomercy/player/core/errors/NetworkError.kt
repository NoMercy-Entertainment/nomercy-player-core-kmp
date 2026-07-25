// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The request did not complete: DNS, offline, reset, or an HTTP status the
// caller can read back with isHttp.
public open class NetworkError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
    suggestion: String? = null,
) : PlayerError(code, scope, severity, message, cause, context, suggestion)

// 401 and 403. Extends NetworkError rather than PlayerError directly so a
// retry-on-network handler keeps catching it, which is web parity and also the
// behaviour you want: an expired token is a request that failed, and refreshing
// then retrying is the same shape as any other retry.
public open class AuthError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
    suggestion: String? = null,
) : NetworkError(code, scope, severity, message, cause, context, suggestion)
