// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

private const val STATUS_CODES_PER_CENTURY = 100

// Base class for everything the player can fail with.
//
// OPEN, never sealed. A sealed taxonomy would mean a plugin cannot raise a
// player error without one of ours being close enough, and the whole point of
// the namespaced [code] is that a plugin mints its own. Sealing this would turn
// the error model from a vocabulary into a wall.
//
// [context] is an untyped bag on purpose — it carries whatever the failing
// layer knew (an HTTP status, a fragment index, a key-system name) to whoever
// reads the log, and typing it would mean a subclass per shape.
public open class PlayerError(
    public val code: String,
    public val scope: ErrorScope,
    public val severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    public val context: Map<String, Any?> = emptyMap(),
    public val suggestion: String? = null,
) : Exception(message ?: code, cause) {

    private var handled: Boolean = false

    // Whether a listener has already dealt with this.
    //
    // The same error object reaches several handlers: the severity channel, the
    // scoped channel, a consumer's own generic pipeline. One of them recovering
    // does not stop the others running, so without a flag a failure that was
    // fixed still gets reported, logged and shown to the viewer by whatever is
    // downstream. This is how the one that recovered says so.
    //
    // Mutable state on an exception rather than a new object, deliberately: the
    // handlers are holding THIS instance, and a copy would leave every one of
    // them looking at the old flag.
    public fun isHandled(): Boolean = handled

    // Deliberately one-way. Un-marking would let a later handler undo an
    // earlier one's recovery, and nothing downstream can know better than the
    // code that actually fixed it.
    public fun markHandled() {
        handled = true
    }

    // True when context["httpStatus"] is in the requested century, so a caller
    // can ask "was this a 5xx" without unpacking the bag. is Int rather than a
    // cast: the bag is untyped and a String "503" must not answer yes.
    public fun isHttp(century: Int): Boolean {
        val status: Any? = context["httpStatus"]
        return status is Int &&
            status >= century * STATUS_CODES_PER_CENTURY &&
            status < (century + 1) * STATUS_CODES_PER_CENTURY
    }
}

// The player was asked to do something its current state cannot do — play an
// empty queue, seek before setup. Code: core:state/<reason>.
public open class StateError(
    code: String,
    scope: ErrorScope = ErrorScope.core(),
    severity: Severity = Severity.ERROR,
    message: String? = null,
    cause: Throwable? = null,
    context: Map<String, Any?> = emptyMap(),
) : PlayerError(code, scope, severity, message, cause, context)

public fun stateError(
    code: String,
    message: String,
    context: Map<String, Any?> = emptyMap(),
): StateError = StateError(
    code = code,
    scope = ErrorScope.core(),
    message = "$code: $message",
    context = context,
)
