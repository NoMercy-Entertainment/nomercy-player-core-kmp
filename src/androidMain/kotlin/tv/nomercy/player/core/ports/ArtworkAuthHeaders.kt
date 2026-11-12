// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The headers a car, a notification or a lock screen needs to fetch the
// artwork [MediaSessionPlugin.nowPlayingFor] points at.
//
// A NoMercy image sits behind the same Keycloak gate as everything else on the
// server, and Media3's own default BitmapLoader fetches artworkUri with a bare
// HttpDataSource that carries no Authorization header — the request the engine
// itself makes for the audio does carry one, through this library's own
// AuthHeaders, but that instance belongs to one playback engine and this
// bitmap fetch happens on the session, which outlives any one engine and is
// built before either exists. Android Auto, a Bluetooth head unit and the lock
// screen all read this same session's artwork, so a 401 here is silent
// everywhere at once: the title and the transport controls still show, and
// only the cover is missing, which reads as "the app forgot the artwork"
// rather than "the request was refused."
//
// Installed the same way, and read the same way, as [MediaNotificationBranding]
// beside it — a provider rather than a captured value, because the token this
// answers with today is not the one that answers tomorrow.
//
// The provider gets the URL so the token goes only to the host that owns it,
// never to a third-party cover host.
@Volatile
private var installedArtworkAuthHeaders: (() -> Map<String, String>)? = null

@Volatile
private var installedArtworkRequestHeaders: ((url: String) -> Map<String, String>)? = null

public fun PlatformEnvironment.installArtworkRequestHeaders(provider: (url: String) -> Map<String, String>) {
    installedArtworkRequestHeaders = provider
}

@Deprecated(
    "Sends the headers to every artwork host. Use installArtworkRequestHeaders, which gets the URL.",
    ReplaceWith("installArtworkRequestHeaders { _ -> provider() }"),
)
public fun PlatformEnvironment.installArtworkAuthHeaders(provider: () -> Map<String, String>) {
    installedArtworkAuthHeaders = provider
}

public val PlatformEnvironment.artworkAuthHeaders: (() -> Map<String, String>)?
    get() = installedArtworkAuthHeaders

internal fun clearArtworkHeaderProviders() {
    installedArtworkAuthHeaders = null
    installedArtworkRequestHeaders = null
}

internal fun artworkHeadersFor(url: String): Map<String, String> =
    installedArtworkRequestHeaders?.invoke(url) ?: installedArtworkAuthHeaders?.invoke().orEmpty()

// Adds the provider's headers for this request's URL; a header the request
// already carries is never overwritten.
internal val artworkHeaderInterceptor: okhttp3.Interceptor = okhttp3.Interceptor { chain ->
    val request = chain.request()
    val extra = artworkHeadersFor(request.url.toString())
    if (extra.isEmpty()) {
        chain.proceed(request)
    } else {
        val builder = request.newBuilder()
        for ((name, value) in extra) {
            if (request.header(name) == null) builder.header(name, value)
        }
        chain.proceed(builder.build())
    }
}
