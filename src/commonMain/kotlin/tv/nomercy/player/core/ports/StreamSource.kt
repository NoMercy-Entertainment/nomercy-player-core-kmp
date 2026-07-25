// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// How a consumer teaches the player a protocol it does not know.
//
// Not the adaptive-bitrate path — that is MediaBackend. This is the extension
// seam: register a factory, and URLs it claims get routed through it. Nothing
// in the player requires one to exist.
public interface StreamSource {
    public val kind: StreamKind

    // No surface parameter, unlike the web's attach(HTMLMediaElement): there is
    // no common video element type across these platforms, so the backend owns
    // the wiring and this only says when to do it.
    public suspend fun attach()
    public fun detach()
    public fun destroy()

    public fun state(): StreamSourceState
    public fun levels(): List<StreamLevel>

    public fun on(event: String, fn: (Any?) -> Unit)
    public fun off(event: String, fn: (Any?) -> Unit)
}

public data class StreamFactoryOptions(
    val url: String,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

// [canPlay] is asked about every resolved URL, so it must be cheap and must not
// claim URLs it cannot actually handle: a false yes takes the URL away from the
// backend that would have played it.
public interface StreamFactory {
    public val id: String
    public fun canPlay(url: String, contentType: String? = null): Boolean
    public fun create(opts: StreamFactoryOptions): StreamSource
}
