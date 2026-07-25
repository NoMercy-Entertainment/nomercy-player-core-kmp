// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// Protected content could not be played: key system denied, licence request
// refused, key expired mid-playback. Code: core:drm/<reason>.
public open class DrmError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)
