// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The registry against the contract it was generated from.
//
// The generator preserves existing keys and adds the rest, which means it can
// never remove one by accident — but it also means nothing stops the contract
// moving underneath the committed file. This is what notices: a new event, a
// removed event or a renamed one all show up here as a set difference with the
// names printed, rather than as a listener that silently never fires.
//
// JVM-only because reading a file is trivial here and pointless to make work on
// six other targets for the same answer.
class CoreEventsRegistryTest {

    private fun contractBaseEventNames(): Set<String> {
        // Vendored so this repo runs standalone in CI, where the generator's
        // output does not exist. scripts/sync-contract.py refreshes it.
        val file = File("contract/contract.json")
        assertTrue(file.exists(), "no vendored contract at ${file.absolutePath}")

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("events").jsonArray
            .map { it.jsonObject }
            .filter { it["map"]?.jsonPrimitive?.content == "base" }
            .map { it.getValue("name").jsonPrimitive.content }
            .toSet()
    }

    @Test
    fun theRegistryCoversExactlyTheContractsBaseEvents() {
        val declared: Set<String> = CoreEvents.all.map { it.name }.toSet()
        val contract: Set<String> = contractBaseEventNames()

        assertEquals(
            emptySet(),
            contract - declared,
            "the contract has events the registry does not: a listener for one would never fire",
        )
        assertEquals(
            emptySet(),
            declared - contract,
            "the registry has events the contract does not: nothing on the other ecosystems emits them",
        )
    }

    @Test
    fun noEventNameIsRegisteredTwice() {
        val names: List<String> = CoreEvents.all.map { it.name }

        // Two properties with the same wire name means one of them is dead: the
        // listener registers under a name something else is emitting.
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun theRegistryIsNotEmptyAndCarriesTheSeamsItIsAboutParityFor() {
        assertEquals(152, CoreEvents.all.size)

        val names = CoreEvents.all.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("play", "beforePlay", "playPrevented", "stream:error", "phase")))
    }

    @Test
    fun everyBeforeEventHasThePreventedEventThatAnswersIt() {
        val names: Set<String> = CoreEvents.all.map { it.name }.toSet()

        // beforeSetup is the one before-event with no prevented counterpart, and
        // it is right that it has none: a refused setup means there is no player
        // to hear the answer, so the caller gets it from setup() itself. Every
        // other before-event happens on a player that is already running.
        val befores: List<String> = names.filter { it.startsWith("before") && it != "beforeSetup" }

        // beforeX without XPrevented means a listener can refuse the action and
        // nothing tells the caller why it did not happen.
        val orphaned = befores.filter { before ->
            val action = before.removePrefix("before").replaceFirstChar { it.lowercase() }
            "${action}Prevented" !in names
        }

        assertEquals(emptyList(), orphaned)
    }
}
