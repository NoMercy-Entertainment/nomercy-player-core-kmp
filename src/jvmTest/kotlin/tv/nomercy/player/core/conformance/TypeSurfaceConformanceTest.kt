// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import tv.nomercy.player.conformance.ContractFixture

/**
 * Every type the web trio exports, and whether this port has one.
 *
 * The method surface measured 94% and the type surface 59% on the same day, and
 * that gap is the whole reason this file exists. A method name is the shallowest
 * thing a port can copy — a name and an arity — while the types behind it are
 * what a consumer actually programs against. A port can pass a method check with
 * no way to express a single cast-sender option.
 *
 * So this fails the build for a web type with no counterpart here, exactly as
 * [MethodSurfaceConformanceTest] fails it for a method this port invented. The
 * two directions are different failures: inventing is drift, missing is an
 * unfinished port, and only one of them was walled until now.
 */
class TypeSurfaceConformanceTest {

    /**
     * Types the web has because the web is a browser, with what stands in their
     * place here.
     *
     * Each one is a decision, not an exemption. A native player growing a
     * `CreateElement` to satisfy a count would be worse than not having one, and
     * the rule is that the CAPABILITY must exist under this port's own name —
     * otherwise the entry belongs in the missing list, not this one.
     */
    private val webOnly: Map<String, String> = mapOf(
        // The DOM element factory. There is no document to create nodes in; the
        // Compose and SwiftUI chromes build their own views.
        "CreateElement" to "no DOM; the toolkit builds views",
        "AddClasses" to "no DOM; styling is the toolkit's",
        "AppendTo" to "no DOM; the toolkit owns the tree",

        // Browser storage engines. The capability is Storage, whose actual per
        // platform is DataStore, UserDefaults and a file on the desktop.
        "IndexedDBBackend" to STORAGE_PORT,
        "LocalStorageBackend" to STORAGE_PORT,
        "MemoryStorageBackend" to "Storage port; InMemoryStorage in the testing kit",

        // hls.js. The native engines demux HLS themselves — ExoPlayer, mpv and
        // AVFoundation each read a master playlist without help.
        "HlsHandle" to ENGINE_DEMUXES_HLS,
        "HlsLoaderConfig" to ENGINE_DEMUXES_HLS,
        "HlsStreamSource" to ENGINE_DEMUXES_HLS,

        // A <video> element and the bridge to it.
        "MediaElementBackend" to "VideoBackend; per-engine actual replaces it",
        "DomBridgeHandler" to "no DOM to bridge to",

        // Vite's import.meta.glob. Kotlin has no build-time module glob; the
        // translator loads from resources.
        "GlobModule" to NO_MODULE_GLOB,
        "GlobLazyLoader" to NO_MODULE_GLOB,
        "LazyTranslationLoader" to NO_MODULE_GLOB,
    )

    private val contract = ContractFixture.read()

    // Named once. The same reason applies to several types and detekt is right
    // that three copies of a sentence is three places to edit it.
    private companion object {
        const val STORAGE_PORT = "Storage port; the platform actual replaces it"
        const val ENGINE_DEMUXES_HLS = "the engine demuxes HLS itself"
        const val NO_MODULE_GLOB = "no import.meta.glob in Kotlin"

        // TypeScript's I-prefix on an interface, which Kotlin does not use.
        val HUNGARIAN_INTERFACE = Regex("^I[A-Z]\\w*$")
    }

    private val webTypes = contract["types"]!!.jsonArray

    /**
     * The types this port does not have yet, listed one by one.
     *
     * A RATCHET, not a licence. The test asserts the missing set equals this
     * list EXACTLY, so a new gap fails the build the day it appears and a ported
     * type fails it until the name is struck from here. The port is finished
     * when this list is empty, and the number in the failure message is the only
     * honest answer to "how far along is it".
     *
     * 49 of 203 at the time of writing, against a method-name check that read
     * 94% on the same source. Methods are a name and an arity; these are what a
     * consumer programs against.
     */
    private val notPortedYet: Set<String> = setOf(
        "AriaLiveLevel",
        "AuthConfig",
        "AuthHeaderProvider",
        "AuthHeaderValue",
        "BackendId",
        "BackendLifecycleBridgeOptions",
        "BackendLifecycleSource",
        "BackendLoaderState",
        "BaseEventMap",
        "BasePlayerConfig",
        "BasePlaylistItem",
        "CanvasRenderFn",
        "CastConfig",
        "CastMediaInfo",
        "CastMediaMetadata",
        "CastSenderEvents",
        "CastSenderOptions",
        "CastSenderPlugin",
        "CastSenderTranslationKey",
        "ChromeCastMediaCtors",
        "CueEventPayload",
        "DefaultTranslator",
        "DefaultTranslatorOptions",
        "DisplayRangeProbe",
        "EqBandFrequency",
        "EqualizerEvents",
        "IEventBus",
        "IFetch",
        "ILanguageMatcher",
        "IPlayerBackend",
        "ISubtitleRenderer",
        "LrcWordCue",
        "MediaListEvent",
        "MessageInput",
        "MinimalBackendEventPayload",
        "NetworkTranslationLoader",
        "NetworkTranslationLoaderOptions",
        "PlayerConstructorId",
        "PlayerExperimental",
        "PluginCtorWithId",
        "PluginSpec",
        "PreventedReason",
        "RequireSpec",
        "StreamErrorPayload",
        "StreamEventPayloadMap",
        "TranslationLoader",
        "VTTSpritePayload",
        "VTTSubtitlePayload",
        "WithCurrentItem",
    )

    @Test
    fun theMissingSetIsExactlyTheOneWeHaveAccountedFor() {
        val declared: Set<String> = kotlinDeclarations()

        val missing: Set<String> = webTypes
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .filterNot { name -> name in webOnly }
            .filterNot { name -> isPresent(name, declared) }
            .toSet()

        val appeared: Set<String> = missing - notPortedYet
        val ported: Set<String> = notPortedYet - missing

        if (appeared.isNotEmpty() || ported.isNotEmpty()) {
            val total: Int = webTypes.size
            val lines: MutableList<String> = mutableListOf(
                "web type surface: ${total - missing.size - webOnly.size} ported, " +
                    "${missing.size} outstanding, ${webOnly.size} web-only",
            )

            if (appeared.isNotEmpty()) {
                lines += "NEW GAPS - port them, or adjudicate them as web-only with a reason:"
                lines += appeared.sorted().map { name -> "    $name" }
            }

            if (ported.isNotEmpty()) {
                lines += "PORTED - strike these from notPortedYet:"
                lines += ported.sorted().map { name -> "    $name" }
            }

            fail(lines.joinToString(separator = System.lineSeparator()))
        }
    }

    // The adjudication list only ever shrinks by porting. An entry naming a type
    // the web no longer exports is a decision about nothing, and it hides the
    // day that type comes back under the same name.
    @Test
    fun everyAdjudicatedTypeIsStillOneTheWebExports() {
        val exported: Set<String> = webTypes
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .toSet()

        assertEquals(
            emptySet(),
            webOnly.keys - exported,
            "adjudicated as web-only but the web no longer exports them",
        )
    }

    /**
     * The same name in Kotlin's idiom.
     *
     * TypeScript prefixes an interface with I and Kotlin does not — the port's
     * `Clock` IS the web's `IClock`, and scoring those as missing put the first
     * measurement at 44.8% when the honest figure was 59.1%. A ruler that is
     * wrong in the port's favour is bad; one wrong against it is just as bad,
     * because it sends work at something already done.
     */
    private fun isPresent(webName: String, declared: Set<String>): Boolean =
        webName in declared || kotlinName(webName) in declared

    private fun kotlinName(webName: String): String =
        if (HUNGARIAN_INTERFACE.matches(webName)) webName.substring(1) else webName

    // Read from source rather than by reflection, because a type can be present
    // and correct without ever appearing on ComposedPlayer's signature — most of
    // these are payloads, options and enums that only a consumer names.
    private fun kotlinDeclarations(): Set<String> {
        val keyword = Regex(
            """\b(?:class|interface|object|enum class|value class|typealias|fun interface)\s+`?(\w+)`?""",
        )

        return sources()
            .flatMap { file -> keyword.findAll(file.readText()).map { match -> match.groupValues[1] } }
            .toSet()
    }

    private fun sources(): Sequence<File> {
        val roots: List<File> = listOf(
            File("src"),
            File("../nomercy-video-player-kmp"),
            File("../nomercy-music-player-kmp/src"),
        ).filter(File::isDirectory)

        return roots.asSequence()
            .flatMap { root -> root.walkTopDown() }
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.path.replace('\\', '/').contains("/build/") }
    }
}
