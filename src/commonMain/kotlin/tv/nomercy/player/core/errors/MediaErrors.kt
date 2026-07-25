// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The bytes arrived and could not be decoded: unsupported codec, corrupt
// container. Retrying will not help; a different rendition might.
// Code: core:media/<reason>.
public open class MediaFormatError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)

// The streaming pipeline failed — manifest, fragment, level switch. The scope
// carries which stream. Code: core:stream/<reason>.
//
// Shares its name with the events package's StreamError, which is the payload
// of the stream:error event. The web trio has the same pair for the same
// reason: one is what went wrong, the other is what listeners are told about
// it. They are never imported into the same file.
public open class StreamError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)

// Something the player needed to load did not load: a worker, a native
// library, a sidecar file. Code: core:resource/<reason>.
public open class ResourceError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)

public fun mediaFormatError(
    code: String,
    message: String,
    context: Map<String, Any?> = emptyMap(),
): MediaFormatError = MediaFormatError(
    code = code,
    scope = ErrorScope.core(),
    message = "$code: $message",
    context = context,
)

public fun resourceError(
    code: String,
    message: String,
    context: Map<String, Any?> = emptyMap(),
): ResourceError = ResourceError(
    code = code,
    scope = ErrorScope.core(),
    message = "$code: $message",
    context = context,
)
