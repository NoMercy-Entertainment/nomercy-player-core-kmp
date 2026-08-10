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
import tv.nomercy.player.conformance.ContractFixture

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
    // The browser's own output-device picker, Chrome only. No native platform
    // has a picker to open — routing to a device you already know is
    // audioOutput(id), which this player has. It used to be spelled
    // selectAudioOutput(id) here, which is a different method wearing the
    // contract's name for this one.
    "selectAudioOutput",
    // A runtime method-override table. The web needs one because a
    // mixin-composed player cannot be subclassed; Kotlin's answer is that every
    // member here is already open, so a consumer replacing one behaviour
    // overrides one method. Reproducing this would mean reflection in a shipped
    // artifact to offer a worse version of what the language does.
    "experimental",
)

// Subsystems this port has not reached.
//
// Empty, and that is the whole point of it having been written out rather than
// derived: a set difference that quietly got smaller for the wrong reason would
// have looked like progress the entire way down from ninety.
//
// It stays here rather than being deleted with its last entry. The next method
// the contract gains lands in the missing set with nowhere to go, and a gate
// that fails with "this needs a reason, not a blank" is more use than one that
// has to be re-invented first.
private val NOT_YET_PORTED_METHODS: Set<String> = emptySet()

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

    private fun contractMethods(player: String): Set<String> = ContractFixture.methods(player)

    private fun contractBaseMethods(): Set<String> = ContractFixture.baseMethods()

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
        //
        // The emitter methods are here for a different reason than the rest.
        // The web player extends EventEmitter, so on / once / off / emit /
        // onAll are inherited and the generator, which reads the player class's
        // own declarations, never sees them. They are the same methods on both
        // sides; only the contract cannot say so.
        val NATIVE_ONLY = setOf(
            "context", "transport", "volume", "time", "state", "lifecycle", "bridge",
            "activity", "cueParsers", "streamFactories",
            "stateFlow", "rootLogger", "rootStorage", "emit", "on", "onAll", "once", "off",
            "dispatchBefore", "fetch", "websocket", "report", "aspectRatio",
            "pluginList", "contributions", "coreVersion", "formatTitle",
            // The reference reaches its preload machinery from
            // AutoAdvancePlugin.preloadNext, which loads into the browser's
            // "next" slot through player.load(item, { slot: 'next' }).
            // LoadOptions here carries no slot — the engines expose a real
            // secondary rather than a cache to warm — so the same capability
            // has to be reachable some other way, and this is it. The plugin
            // method it serves IS on the contract; only this seam is ours.
            "preloadNow",
            // The reference reaches AirPlay through transferTo(AIRPLAY) — one
            // seam for both casting and system-level output routing, because a
            // DOM element you can call requestAirplay-adjacent APIs on already
            // holds the destination. AirPlayHandoff's own note explains why
            // that seam is wrong here: an AirPlay route has no item or position
            // to hand over and must not pause first, where a CastSender
            // transfer must. Two behaviours that must not share a guard get
            // two methods instead.
            "transferToExternal",
        )
    }
}
