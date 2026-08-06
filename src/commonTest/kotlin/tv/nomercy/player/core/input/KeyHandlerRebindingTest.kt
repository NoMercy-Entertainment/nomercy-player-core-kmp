// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import tv.nomercy.player.core.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Remapping a key from outside the plugin.
//
// The binding table has had bind/replace/unbind all along and the plugin kept
// it protected, so the only way to change a key was to write a subclass. A
// settings screen offering "press a key for skip forward" could not be built
// against that at all — which is why the reference puts these on the plugin.
class KeyHandlerRebindingTest {

    private object TestManifest : PluginManifest {
        override val id: String = "test/key-handler"
        override val version: String = "0.0.0"
    }

    private class Handler : KeyHandlerPlugin<Unit>(nowMs = { 0L }) {
        override val manifest: PluginManifest = TestManifest
        override val options: Unit = Unit
        override fun addDefaults() = Unit
    }

    @Test
    fun aConsumerCanBindACombAndHaveItFire() {
        val plugin = Handler()
        var fired = 0
        plugin.bind("shift+ArrowLeft") { fired += 1 }

        assertTrue(plugin.handle(keyCombo("ArrowLeft", shift = true)))
        assertEquals(1, fired)
    }

    // Two spellings of one chord are one binding. The table is keyed on the
    // canonical form, so a combo read back out of stored preferences in a
    // different case has to reach the same entry rather than adding a second.
    @Test
    fun modifierCaseAndOrderDoNotMakeASecondBinding() {
        val plugin = Handler()
        var fired = 0
        plugin.bind("Shift+ArrowLeft") { fired += 1 }
        plugin.bind("shift+ArrowLeft") { fired += 10 }

        plugin.handle(keyCombo("ArrowLeft", shift = true))

        assertEquals(1, plugin.bindings().size)
        assertEquals(10, fired)
    }

    @Test
    fun unbindingStopsTheKeyFromBeingOurs() {
        val plugin = Handler()
        plugin.bind("ArrowRight") { }
        plugin.unbind("ArrowRight")

        assertFalse(plugin.handle(keyCombo("ArrowRight")))
    }

    // Replacing runs the NEW action, which is the whole point of the alias, and
    // the table clears the cooldown with it so the new one is not made to wait
    // out the old one's.
    @Test
    fun replacingSwapsTheActionRatherThanAddingOne() {
        val plugin = Handler()
        val ran: MutableList<String> = mutableListOf()
        plugin.bind("KeyF") { ran += "old" }
        plugin.replace("KeyF") { ran += "new" }

        plugin.handle(keyCombo("KeyF"))

        assertEquals(listOf("new"), ran)
    }

    // The snapshot is a copy: dropping an entry from it unbinds nothing, and it
    // runs whatever is live when it is called rather than what was bound when
    // it was taken.
    @Test
    fun theSnapshotIsACopyThatFollowsTheLiveTable() {
        val plugin = Handler()
        val ran: MutableList<String> = mutableListOf()
        plugin.bind("KeyM") { ran += "first" }

        val taken: Map<String, () -> Unit> = plugin.bindings()
        plugin.replace("KeyM") { ran += "second" }
        taken.getValue(keyCombo("KeyM").canonical).invoke()

        assertEquals(listOf("second"), ran)
        assertTrue(plugin.handle(keyCombo("KeyM")))
    }
}
