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
import tv.nomercy.player.core.controllers.ComposedPlayer
import java.io.File
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Methods that exist on the web player because the web has a DOM. There is no
// element to add a class to and no SVG to create, and a native player growing
// them to satisfy a count would be worse than not having them.
private val WEB_ONLY_METHODS = setOf(
    "addClasses",
    "removeClasses",
    "container",
    "createButton",
    "createElement",
    "createSVG",
    "audioContext",
)

// Subsystems this port has not reached. The list shrinking is the measure of the
// port, which is why it is written out rather than derived — a set difference
// that quietly got smaller for the wrong reason would look like progress.
private val NOT_YET_PORTED_METHODS = setOf(
    "activityTracking", "announce", "audioOutput", "audioOutputs",
    "audioTrackMode", "auth", "backend", 
    "baseUrl", "bufferState", "bufferedRanges", "bumpActivity", "canPlay", "castState",
    "device", "dispatching", "enabledPlugins", "experimental",
    "getPluginById", "getStreamFactory", "isDesktop", "isMobile", "isTv",
    "load", "loadQueue", "metrics", "networkState", "now",
    "options", "platform", "playNow", "playerId",
    "preloadStrategy", "recordMetric", "refreshAuth", "registerCueParser", "registerStream",
    "registerTitleTokens", "resolveCueParser", "resolveUrl",
    "seekable",
    "selectAudioOutput", "setPreloadStrategy", "setTransitionStrategy", "streamState",
    "streams", "timeData", "transferTo", "transitionStrategy",
    "unregisterCueParser", "unregisterStream", "urlResolver",
    "visibilityState", 
)

// The player's own method names against the contract's.
//
// This is the half of the shape gate that reflection is for. The event registry
// and the error catalog are declared lists that can be compared without it; the
// player's surface is whatever its class actually exposes, and asking the class
// is the only way to notice a method that was renamed, never written, or
// invented here.
//
// It is jvmTest-only and so is kotlin-reflect. A reflection library in the
// shipped artifact would be a megabyte every consumer carries so that a test
// could ask a question at build time.
//
// The base surface is derived rather than declared: the contract tags each
// method with the player that exposes it, and a method on both the video and
// music players is one they inherit. That derivation is asserted below, because
// if the extractor ever stops tagging them the intersection would quietly empty
// and this gate would pass by comparing nothing.
class MethodSurfaceConformanceTest {

    private fun contractMethods(player: String): Set<String> {
        val file = File("contract/contract.json")
        assertTrue(file.exists(), "no vendored contract at ${file.absolutePath}")

        return Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("methods").jsonArray
            .map { it.jsonObject }
            .filter { it["player"]?.jsonPrimitive?.content == player }
            .map { it.getValue("name").jsonPrimitive.content }
            .toSet()
    }

    private fun contractBaseMethods(): Set<String> =
        contractMethods("video") intersect contractMethods("music")

    // Everything a caller can reach on the player, by name. Properties count:
    // the contract does not distinguish `player.plugins` from `player.plugins()`
    // and neither does a consumer reading a name in a doc.
    private fun playerSurface(): Set<String> {
        val functions: List<String> = ComposedPlayer::class.memberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
        val properties: List<String> = ComposedPlayer::class.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }

        return (functions + properties).toSet() - OBJECT_METHODS
    }

    @Test
    fun theBaseSurfaceIsDerivedFromSomethingRatherThanEmpty() {
        val base: Set<String> = contractBaseMethods()

        assertTrue(
            base.size > MINIMUM_BASE_SURFACE,
            "only ${base.size} methods are common to both players: the contract's tagging changed " +
                "and this gate is now comparing almost nothing",
        )
        assertTrue(base.contains("play") && base.contains("pause"), "the base surface has no transport")
    }

    @Test
    fun corePlayerInventsNoMethodTheContractDoesNotHave() {
        // A method here and nowhere else is a divergence a consumer meets as a
        // difference between platforms. The exceptions are the ones native
        // needs and the web cannot have.
        val invented: Set<String> = playerSurface() -
            contractBaseMethods() -
            contractMethods("video") -
            contractMethods("music") -
            NATIVE_ONLY

        assertEquals(
            emptySet(),
            invented,
            "core exposes methods the contract does not name",
        )
    }

    @Test
    fun everyContractMethodCoreLacksHasAStatedReason() {
        val missing: Set<String> = contractBaseMethods() - playerSurface()
        val accounted: Set<String> = WEB_ONLY_METHODS + NOT_YET_PORTED_METHODS

        assertEquals(
            emptySet(),
            missing - accounted,
            "contract methods core neither has nor accounts for: each needs a reason, not a blank",
        )
    }

    @Test
    fun theReasonListsDoNotOutliveTheMethodsTheyExcuse() {
        // A method that has been written and left on a list makes the remaining
        // port look bigger than it is; one that has left the contract is a
        // promise to nobody.
        val accounted: Set<String> = WEB_ONLY_METHODS + NOT_YET_PORTED_METHODS

        assertEquals(
            emptySet(),
            accounted intersect playerSurface(),
            "these methods exist on the player and are still excused as missing",
        )
        assertEquals(
            emptySet(),
            accounted - contractBaseMethods(),
            "these methods are excused but are not in the contract's base surface",
        )
    }

    private companion object {
        const val MINIMUM_BASE_SURFACE = 100

        // Inherited from Any, and not part of anyone's contract.
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")

        // Things the native port has that the web does not need a name for: the
        // controllers it is composed of, and the Kotlin-shaped state surface.
        val NATIVE_ONLY = setOf(
            "context", "transport", "volume", "time", "state", "lifecycle", "bridge",
            "stateFlow", "rootLogger", "rootStorage", "emit", "on", "once", "off",
            "dispatchBefore", "fetch", "websocket", "report", "aspectRatio",
            "pluginList", "contributions", "coreVersion",
        )
    }
}
