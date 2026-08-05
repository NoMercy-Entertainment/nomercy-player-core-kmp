// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.StreamError

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

// Which factory claims a url.
//
// Same shape and same ordering rule as the cue parsers, deliberately: a
// consumer who has learned one registry has learned both, and two extension
// points that behaved differently would be two things to remember.
//
// Most-recently-registered wins, so overriding a built-in is registering one
// that does the same job. A built-in seeds itself at the lowest priority
// instead, because registration order is the wrong lever for something
// registered first by construction.
public class StreamRegistry {

    private val factories: MutableList<StreamFactory> = mutableListOf()

    public fun register(factory: StreamFactory, atLowestPriority: Boolean = false) {
        factories.removeAll { it.id == factory.id }
        if (atLowestPriority) factories.add(0, factory) else factories.add(factory)
    }

    public fun unregister(id: String) {
        factories.removeAll { it.id == id }
    }

    // Null when nothing claims it, which is the ordinary answer: most urls are
    // played by the backend directly and never want a factory at all. Treating
    // absence as an error would make every plain mp4 a failure.
    public fun resolve(url: String, contentType: String? = null): StreamFactory? =
        factories.lastOrNull { it.canPlay(url, contentType) }

    // Resolve, or say why not.
    //
    // The reverse of resolve()'s question. That one asks whether a factory
    // wants this url and null is an ordinary no; this one is called when a
    // factory is REQUIRED, and returning null there pushed the failure down the
    // stack to whatever dereferenced it — a null pointer where the contract has
    // a named code.
    public fun create(opts: StreamFactoryOptions): StreamSource {
        val factory: StreamFactory = resolve(opts.url, opts.contentType)
            ?: throw StreamError(
                code = CoreErrorCodes.NO_FACTORY_MATCH,
                scope = ErrorScope.core(),
                message = "No stream factory could play ${opts.url}" +
                    (opts.contentType?.let { " ($it)" } ?: "") +
                    ". Register a factory via player.registerStream(...) first.",
                context = mapOf("url" to opts.url, "contentType" to opts.contentType),
            )

        return factory.create(opts)
    }

    public fun findById(id: String): StreamFactory? = factories.firstOrNull { it.id == id }

    // Highest priority first, which is the reverse of the order they are held
    // in. A consumer reading this to decide what to override should see what
    // resolve() would pick at the top.
    public fun list(): List<String> = factories.asReversed().map { it.id }

    public fun dispose() {
        factories.clear()
    }
}
