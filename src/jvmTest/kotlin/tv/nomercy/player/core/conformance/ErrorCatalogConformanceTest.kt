// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorCode
import tv.nomercy.player.core.plugin.PluginErrorCodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.conformance.ContractFixture

// Codes that cannot exist away from a browser: a DOM element the caller never
// passed, an HLS attach that only MSE performs. Nothing native can raise these,
// and holding them open would mean a permanent gap nobody is meant to close.
private val WEB_ONLY = setOf(
    "core:player/element-missing",
    "core:player/element-not-div",
    "core:player/no-element",
    "core:player/invalid-id-type",
    "core:media/hls-unsupported",
    "core:stream/hls-attach-failed",
)

// Raised by things the web package ships alongside the player rather than by
// the player: a Vite translation loader and the plugin-test harness. They are
// in the contract because the extractor sweeps the package, and they carry
// core's namespace because they were written before the namespace scheme meant
// what it means now — an adapter owns its own ids. Renaming them is a breaking
// change for anyone matching on them, so they are recorded here rather than
// silently absorbed. Nothing native will ever raise one.
private val NOT_THE_PLAYER = setOf(
    "core:state/vite-plugin-not-configured",
    "core:test/getplugin-returned-undefined",
    "core:test/listener-leak",
    "core:test/player-missing-addplugin",
    "core:test/vitest-globals-missing",
)

// Real failures of subsystems this port has not reached. Every one is a code
// core will raise once the subsystem lands, which is why they are listed by
// hand: the list shrinking is the measure of the port, and a code disappearing
// from it without the subsystem arriving is a regression this test catches.
private val NOT_YET_PORTED = setOf(
    "core:auth/refresh-failed",
    "core:drm/license-url-missing",
    "core:media-tracks/no-active-item",
    "core:media/load-failed",
    "core:media/missing-url",
    "core:network/aborted",
    "core:network/bad-gateway",
    "core:network/client-error",
    "core:network/gateway-timeout",
    "core:network/gone",
    "core:network/not-found",
    "core:network/offline",
    "core:network/parse-failed",
    "core:network/rate-limited",
    "core:network/request-timeout",
    "core:network/server-error-other",
    "core:network/service-unavailable",
    "core:player/backend-missing",
    "core:player/crossfade-unsupported",
    "core:player/not-found",
    "core:resource/playlist-fetch-failed",
    "core:stream/no-factory-match",
)

// The error catalog against the contract that names it.
//
// An error code is not an implementation detail: it is the string a consumer
// switches on, a dashboard groups by and a bug report quotes. A code that reads
// core:network/timeout here and core:network/time-out on the web is two
// failures wearing one name, and nothing in either codebase would say so.
//
// The gate is deliberately asymmetric. A code core declares that the contract
// does not have is a hard failure with no way to allow it — it means this port
// invented a failure the rest of the ecosystem cannot recognise. A contract
// code core does not declare is expected during a port, so it is allowed, but
// only against a stated reason.
class ErrorCatalogConformanceTest {

    private fun contractErrorCodes(): Set<String> = ContractFixture.errorCodes()

    private fun declared(): Set<String> = CoreErrorCodes.all + PluginErrorCodes.all

    // Only core's own namespace is measurable here. plugin: and visualization:
    // codes belong to the plugins that raise them, which is the whole point of
    // the namespace: a plugin owns its ids without asking core for a slot.
    private fun contractCoreCodes(): Set<String> =
        contractErrorCodes().filter { it.startsWith("core:") }.toSet()

    @Test
    fun coreDeclaresNoCodeTheEcosystemHasNeverHeardOf() {
        val invented: Set<String> = declared() - contractCoreCodes()

        assertEquals(
            emptySet(),
            invented,
            "core raises codes the contract does not name: a consumer switching on them " +
                "on any other platform would never match",
        )
    }

    @Test
    fun everyContractCodeCoreDoesNotRaiseHasAStatedReason() {
        val missing: Set<String> = contractCoreCodes() - declared()
        val accounted: Set<String> = WEB_ONLY + NOT_THE_PLAYER + NOT_YET_PORTED

        assertEquals(
            emptySet(),
            missing - accounted,
            "contract codes core neither raises nor accounts for: each needs a reason, " +
                "not a blank",
        )
    }

    @Test
    fun theReasonListsDoNotOutliveTheCodesTheyExcuse() {
        // A code that has been implemented and left on a list reads as a gap
        // that is not one, and the port's remaining work would look larger than
        // it is. A code that has left the contract entirely reads as a promise
        // to no one.
        val accounted: Set<String> = WEB_ONLY + NOT_THE_PLAYER + NOT_YET_PORTED

        assertEquals(
            emptySet(),
            accounted intersect declared(),
            "these codes are raised by core and still excused as missing",
        )
        assertEquals(
            emptySet(),
            accounted - contractCoreCodes(),
            "these codes are excused but no longer in the contract",
        )
    }

    @Test
    fun theReasonListsDoNotOverlap() {
        // One reason per code. Two would mean neither is the reason.
        assertEquals(emptySet(), WEB_ONLY intersect NOT_THE_PLAYER)
        assertEquals(emptySet(), WEB_ONLY intersect NOT_YET_PORTED)
        assertEquals(emptySet(), NOT_THE_PLAYER intersect NOT_YET_PORTED)
    }

    @Test
    fun everyDeclaredCodeParsesAsACode() {
        // The scheme is namespace:category/reason and it is parsed strictly, so
        // a code that only nearly matches would be grouped on its own by every
        // dashboard that reads it.
        val malformed: List<String> = declared().filter {
            ErrorCode.parseOrNull(it) == null
        }

        assertEquals(emptyList(), malformed, "malformed codes in the catalog")
    }
}
