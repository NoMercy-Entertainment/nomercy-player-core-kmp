// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What the url is for.
//
// It decides which base a relative path is joined to and, on the two artwork
// categories, whether being left relative is a problem. A plugin may pass
// anything; an unrecognised category resolves like media rather than failing,
// because a plugin inventing a category is not an error.
public enum class UrlCategory(public val token: String) {
    MEDIA("media"),
    SUBTITLE("subtitle"),
    FONT("font"),
    POSTER("poster"),
    SPRITE("sprite"),
    LYRICS("lyrics"),
    CAST("cast"),
    LICENSE("license"),
    ;

    // Poster and cast artwork must come out absolute. The OS media-session API
    // and a Cast receiver fetch them from another process with no page origin
    // to resolve against, so a relative one 404s somewhere the player cannot
    // see it fail.
    public val needsAbsolute: Boolean get() = this == POSTER || this == CAST
}

// A url, parsed into the parts a caller actually asks for.
//
// href is the answer; everything else exists so a caller does not write its own
// parser to get at a query parameter or an extension. relative is the honest
// admission that nothing could be resolved — a caller handing that to a Cast
// receiver has a bug, and this is where it is visible.
public data class ResolvedUrl(
    val raw: String,
    val href: String,
    val scheme: String = "",
    val origin: String = "",
    val path: String = "",
    val extension: String = "",
    val query: String = "",
    val fragment: String = "",
    val relative: Boolean = false,
) {
    override fun toString(): String = href

    // The query as pairs, decoded far enough to read a token or a start time.
    // Repeated keys keep every value: a playlist url with two `t` parameters is
    // malformed, and silently dropping one hides that.
    public fun queryParameters(): List<Pair<String, String>> =
        query.removePrefix("?")
            .split("&")
            .filter { it.isNotEmpty() }
            .map { pair ->
                val separator: Int = pair.indexOf('=')
                if (separator < 0) pair to "" else pair.take(separator) to pair.substring(separator + 1)
            }
}

// What a host gets when it takes over resolution.
//
// defaultResolve is included so a resolver can do its own thing for one
// category and hand the rest back, which is the common case: a consumer that
// signs media urls has no opinion about fonts.
public class UrlResolverContext(
    public val baseUrl: String?,
    public val category: UrlCategory,
    public val defaultResolve: suspend (String) -> ResolvedUrl,
)

public fun interface UrlResolver {
    public suspend fun resolve(url: String, context: UrlResolverContext): ResolvedUrl
}

// The scheme test, and the reason a base is a prefix rather than a URL base.
//
// A scheme is a letter followed by letters, digits and a few punctuation marks,
// then a colon. Checking for "://" instead would call `mailto:` relative, and
// checking for a colon anywhere would call `chapter:1` absolute.
private val ABSOLUTE = Regex("""^[a-zA-Z][a-zA-Z0-9+\-.]*:""")

private val EXTENSION = Regex("""\.([a-zA-Z0-9]+)$""")

public object UrlResolution {

    // Joins [url] to [base] and parses the result.
    //
    // The base is concatenated, not resolved. Standard resolution drops the
    // base's path segment the moment the relative part starts with a slash, so
    // a base of https://cdn.example/images and a path of /t/p/w500/x.jpg would
    // come out as https://cdn.example/t/p/w500/x.jpg — the wrong host directory,
    // and exactly the shape TMDB-style image urls take.
    // Which base to pass is the caller's decision: artwork joins to the image
    // base and everything else to the media base, and that choice belongs where
    // the config is read rather than buried in a parser.
    public fun resolve(url: String, base: String?): ResolvedUrl {
        if (ABSOLUTE.containsMatchIn(url)) return parse(raw = url, href = url)
        if (base.isNullOrEmpty()) return parse(raw = url, href = url)
        return parse(raw = url, href = base.trimEnd('/') + "/" + url.trimStart('/'))
    }

    private fun parse(raw: String, href: String): ResolvedUrl {
        val fragmentAt: Int = href.indexOf('#')
        val fragment: String = if (fragmentAt >= 0) href.substring(fragmentAt) else ""
        val withoutFragment: String = if (fragmentAt >= 0) href.take(fragmentAt) else href

        val queryAt: Int = withoutFragment.indexOf('?')
        val query: String = if (queryAt >= 0) withoutFragment.substring(queryAt) else ""
        val beforeQuery: String = if (queryAt >= 0) withoutFragment.take(queryAt) else withoutFragment

        val scheme: String = ABSOLUTE.find(beforeQuery)?.value?.dropLast(1).orEmpty()
        val origin: String = originOf(beforeQuery, scheme)
        val path: String = if (origin.isEmpty()) beforeQuery else beforeQuery.removePrefix(origin)

        return ResolvedUrl(
            raw = raw,
            href = href,
            scheme = scheme,
            origin = origin,
            path = path,
            extension = EXTENSION.find(path)?.groupValues?.get(1)?.lowercase().orEmpty(),
            query = query,
            fragment = fragment,
            relative = scheme.isEmpty(),
        )
    }

    // Scheme, authority, and nothing after. Absent for the schemes that have no
    // authority at all — data: and blob: urls are one opaque string, and
    // inventing an origin for them would have a caller compare two blobs by a
    // host neither has.
    private fun originOf(url: String, scheme: String): String {
        if (scheme.isEmpty()) return ""
        val afterScheme: String = url.substring(scheme.length + 1)
        if (!afterScheme.startsWith("//")) return ""
        val authorityEnd: Int = afterScheme.drop(2).indexOf('/')
        val authority: String = if (authorityEnd < 0) afterScheme.drop(2) else afterScheme.drop(2).take(authorityEnd)
        return "$scheme://$authority"
    }
}
