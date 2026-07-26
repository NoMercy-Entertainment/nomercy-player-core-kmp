// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The failures core raises, string-identical to the web kit.
//
// Declared rather than written at each throw site because these strings are the
// contract: a consumer switches on one, a dashboard groups by one, a support
// ticket quotes one. A literal typed twice is two codes that look like one.
//
// Plugin registration codes are not here; they live with the registry that
// raises them, in PluginErrorCodes.
public object CoreErrorCodes {

    public const val DISPOSED: String = "core:player/disposed"
    public const val NOT_READY: String = "core:player/not-ready"
    public const val ALREADY_SETUP: String = "core:lifecycle/already-setup"
    public const val CLEANUP_FAILED: String = "core:lifecycle/cleanup-failed"

    public const val NETWORK_TIMEOUT: String = "core:network/timeout"
    public const val SERVER_ERROR: String = "core:network/server-error"
    public const val FRAGMENT_FAILED: String = "core:stream/fragment-failed"

    public const val UNAUTHENTICATED: String = "core:auth/unauthenticated"
    public const val FORBIDDEN: String = "core:auth/forbidden"

    public const val CODEC_UNSUPPORTED: String = "core:media/codec-unsupported"
    public const val QUEUE_EMPTY: String = "core:state/queue-empty"

    // What the conformance gate measures against the contract, and what a
    // consumer can ask to know whether a code came from core or from a plugin.
    public val all: Set<String> = setOf(
        DISPOSED,
        NOT_READY,
        ALREADY_SETUP,
        CLEANUP_FAILED,
        NETWORK_TIMEOUT,
        SERVER_ERROR,
        FRAGMENT_FAILED,
        UNAUTHENTICATED,
        FORBIDDEN,
        CODEC_UNSUPPORTED,
        QUEUE_EMPTY,
    )
}
