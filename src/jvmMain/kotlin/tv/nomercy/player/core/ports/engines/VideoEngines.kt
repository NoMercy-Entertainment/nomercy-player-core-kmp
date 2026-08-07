// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import tv.nomercy.player.core.ports.VideoBackend

/**
 * Which desktop engine a player gets, and why.
 *
 * libmpv is replacing libVLC, and the two have to run side by side while that is
 * proven: the same fixtures, the same contract tests, on the same machine. A
 * registry rather than a constructor call at the call site, because the choice
 * belongs to whoever is running the build and not to the one file that happened
 * to name an engine first.
 *
 * The order in [registered] is the preference order. It is deliberately not
 * alphabetical and not "newest first": an engine leads only once it is proven on
 * this host, and until then the default has to be the one that plays.
 */
public object VideoEngines {

    /** Every engine this build knows how to construct, in preference order. */
    public val registered: List<VideoEngineProvider> = listOf(
        VlcVideoEngineProvider,
        MpvVideoEngineProvider,
    )

    /**
     * The engine a consumer asked for by name, or null when nothing registered
     * answers to it.
     *
     * Case and surrounding space only, because an id typed into a config file
     * arrives with whatever the editor left on it.
     */
    public fun byId(id: String): VideoEngineProvider? =
        registered.firstOrNull { provider -> provider.id == id.trim().lowercase() }

    /**
     * The chosen engine, honouring an explicit request and falling back to the
     * first registered engine that is actually available.
     *
     * An explicit request that is unavailable is NOT quietly swapped out. Asking
     * for mpv and silently getting VLC is how a migration reports itself green
     * while never having run — the caller is told no, with the reason, and
     * decides.
     */
    public fun select(requested: String? = requestedId()): EngineSelection =
        selectFrom(registered, requested)

    /**
     * The same decision over a given list, which is how it is tested.
     *
     * Whether libVLC or libmpv is installed is a property of the machine the
     * test happens to run on; the decision is not, and it has to be the same on
     * a desktop with both and a desktop with neither.
     */
    public fun selectFrom(engines: List<VideoEngineProvider>, requested: String?): EngineSelection {
        val asked: String? = requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (asked != null) {
            val provider: VideoEngineProvider = engines.firstOrNull { engine -> engine.id == asked }
                ?: return EngineSelection.None("no video engine is registered under \"$asked\"")

            return if (provider.isAvailable()) {
                EngineSelection.Chosen(provider)
            } else {
                EngineSelection.None("$asked was requested and is not available here: ${provider.whyUnavailable()}")
            }
        }

        val available: VideoEngineProvider? = engines.firstOrNull { provider -> provider.isAvailable() }
        return available?.let(EngineSelection::Chosen)
            ?: EngineSelection.None(
                engines.joinToString("; ") { provider -> "${provider.id}: ${provider.whyUnavailable()}" },
            )
    }

    /** The chosen engine's backend, or null when none can run here. */
    public fun create(requested: String? = requestedId()): VideoBackend? =
        (select(requested) as? EngineSelection.Chosen)?.provider?.create()

    /**
     * The request, from a system property first and the environment second.
     *
     * Both, because a Gradle run sets properties and a shell run sets
     * environment variables, and a switch that only works in one of the two gets
     * reported as "the flag does nothing".
     */
    public fun requestedId(): String? =
        System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(ENVIRONMENT)?.takeIf { it.isNotBlank() }

    public const val PROPERTY: String = "nomercy.video.engine"
    public const val ENVIRONMENT: String = "NOMERCY_VIDEO_ENGINE"
}

/** What [VideoEngines.select] decided, and why when it decided nothing. */
public sealed interface EngineSelection {
    public data class Chosen(val provider: VideoEngineProvider) : EngineSelection
    public data class None(val reason: String) : EngineSelection
}
