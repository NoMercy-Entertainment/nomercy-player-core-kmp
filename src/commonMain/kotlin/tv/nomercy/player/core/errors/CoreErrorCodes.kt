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

    // The rest of the HTTP ladder, raised by AuthFetch. Named per status where
    // the answer differs — a 410 is never worth retrying and a 429 is worth
    // retrying slowly — and by class where it does not.
    public const val NOT_FOUND: String = "core:network/not-found"
    public const val REQUEST_TIMEOUT: String = "core:network/request-timeout"
    public const val GONE: String = "core:network/gone"
    public const val RATE_LIMITED: String = "core:network/rate-limited"
    public const val CLIENT_ERROR: String = "core:network/client-error"
    public const val BAD_GATEWAY: String = "core:network/bad-gateway"
    public const val SERVICE_UNAVAILABLE: String = "core:network/service-unavailable"
    public const val GATEWAY_TIMEOUT: String = "core:network/gateway-timeout"
    public const val SERVER_ERROR_OTHER: String = "core:network/server-error-other"

    // The request never got an answer, and the answer could not be read.
    public const val OFFLINE: String = "core:network/offline"
    public const val PARSE_FAILED: String = "core:network/parse-failed"

    public const val UNAUTHENTICATED: String = "core:auth/unauthenticated"
    public const val FORBIDDEN: String = "core:auth/forbidden"

    // A 401 that survived a token refresh. Distinct from UNAUTHENTICATED
    // because the answers differ: one is "sign in", the other is "the sign-in
    // you have is broken".
    public const val REFRESH_FAILED: String = "core:auth/refresh-failed"

    public const val CODEC_UNSUPPORTED: String = "core:media/codec-unsupported"
    public const val QUEUE_EMPTY: String = "core:state/queue-empty"

    // HDR content, an SDR screen, nothing able to convert, and a consumer who
    // chose not to show it wrong. Named in the video namespace rather than core's
    // because the web raises it from the video package, and one failure must not
    // wear two names across two platforms.
    //
    // Deliberately absent from [all], and it must stay absent. That set is measured
    // against the contract codes in core's own namespace, and this one is video's,
    // so vendoring a contract that carries it does not make it declarable here.
    // It travels as an error's detail instead.
    public const val HDR_UNPLAYABLE: String = "video:media/hdr-unplayable"

    // A playlist that did not arrive, and one that arrived unreadable. Two
    // codes because they need different answers: the first is worth retrying
    // and the second never will be.
    public const val PLAYLIST_FETCH_ERROR: String = "core:playlist/fetch-error"
    public const val PLAYLIST_PARSE_ERROR: String = "core:playlist/parse-error"

    // What the conformance gate measures against the contract, and what a
    // consumer can ask to know whether a code came from core or from a plugin.
    public val all: Set<String> = setOf(
        DISPOSED,
        NOT_READY,
        ALREADY_SETUP,
        CLEANUP_FAILED,
        NETWORK_TIMEOUT,
        SERVER_ERROR,
        NOT_FOUND,
        REQUEST_TIMEOUT,
        GONE,
        RATE_LIMITED,
        CLIENT_ERROR,
        BAD_GATEWAY,
        SERVICE_UNAVAILABLE,
        GATEWAY_TIMEOUT,
        SERVER_ERROR_OTHER,
        OFFLINE,
        PARSE_FAILED,
        FRAGMENT_FAILED,
        UNAUTHENTICATED,
        FORBIDDEN,
        REFRESH_FAILED,
        CODEC_UNSUPPORTED,
        QUEUE_EMPTY,
        PLAYLIST_FETCH_ERROR,
        PLAYLIST_PARSE_ERROR,
    )
}
