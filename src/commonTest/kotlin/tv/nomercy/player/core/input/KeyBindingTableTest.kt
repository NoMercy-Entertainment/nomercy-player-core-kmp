// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What a press does, and how often it is allowed to do it.
class KeyBindingTableTest {

    private var clock: Long = 0

    private fun table(): KeyBindingTable = KeyBindingTable { clock }

    @Test
    fun aBoundPressRunsItsAction() {
        var hits = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Right) { hits += 1 }

        assertTrue(table.handle(PlayerKey.Right))
        assertEquals(1, hits)
    }

    @Test
    fun anUnboundPressIsNotClaimed() {
        // The platform passes an unclaimed press on, which is what leaves the
        // volume keys and the back button working. Claiming everything is how a
        // television stops responding to its own remote.
        val table: KeyBindingTable = table()

        assertFalse(table.handle(PlayerKey.VolumeUp))
    }

    @Test
    fun aHeldRemoteDoesNotSeekOncePerRepeat() {
        // A remote held down repeats at whatever rate the platform chooses. A
        // seek bound without a cooldown jumps several minutes from a press
        // somebody meant as one step.
        var hits = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Right, cooldownMs = 200) { hits += 1 }

        table.handle(PlayerKey.Right)
        clock += 50
        table.handle(PlayerKey.Right)

        assertEquals(1, hits)
    }

    @Test
    fun onceTheCooldownHasPassedItFiresAgain() {
        // The other half. A cooldown that never expires is a key that works once
        // per session.
        var hits = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Right, cooldownMs = 200) { hits += 1 }
        table.handle(PlayerKey.Right)

        clock += 250
        table.handle(PlayerKey.Right)

        assertEquals(2, hits)
    }

    @Test
    fun aSuppressedRepeatIsStillReportedAsUnclaimed() {
        // It has to be, or the platform never sees the press and the remote
        // feels dead rather than deliberate.
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Right, cooldownMs = 200) { }
        table.handle(PlayerKey.Right)

        clock += 10

        assertFalse(table.handle(PlayerKey.Right))
    }

    @Test
    fun eachKeyKeepsItsOwnCooldown() {
        // Otherwise pressing right then left loses the left, and a viewer
        // correcting an overshoot has to press twice.
        var lefts = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Right, cooldownMs = 200) { }
        table.bind(PlayerKey.Left, cooldownMs = 200) { lefts += 1 }

        table.handle(PlayerKey.Right)
        table.handle(PlayerKey.Left)

        assertEquals(1, lefts)
    }

    @Test
    fun aGuardedBindingDoesNothingWhileItsGuardIsFalse() {
        // A left press that seeks while a menu is open moves the film out from
        // under whoever was reading the menu.
        var hits = 0
        var menuOpen = true
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Left, enabled = { !menuOpen }) { hits += 1 }

        assertFalse(table.handle(PlayerKey.Left))

        menuOpen = false
        assertTrue(table.handle(PlayerKey.Left))
        assertEquals(1, hits)
    }

    @Test
    fun aGuardIsAskedEveryTimeRatherThanOnce() {
        // Read once at binding time it would be a constant, and every state
        // change after that would be ignored.
        var open = false
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Left, enabled = { !open }) { }
        table.handle(PlayerKey.Left)

        open = true

        assertFalse(table.handle(PlayerKey.Left))
    }

    @Test
    fun replacingSwapsTheActionRatherThanAddingASecond() {
        var first = 0
        var second = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Center) { first += 1 }

        table.replace(PlayerKey.Center.asCombo()) { second += 1 }
        table.handle(PlayerKey.Center)

        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun replacingClearsTheCooldownTheOldBindingLeftBehind() {
        // The new action has not run. Making the viewer wait out the old one is
        // a press that does nothing, for a reason nobody could explain.
        var hits = 0
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Center, cooldownMs = 500) { }
        table.handle(PlayerKey.Center)

        table.replace(PlayerKey.Center.asCombo(), cooldownMs = 500) { hits += 1 }

        assertTrue(table.handle(PlayerKey.Center))
        assertEquals(1, hits)
    }

    @Test
    fun unbindingLeavesThePressToThePlatform() {
        val table: KeyBindingTable = table()
        table.bind(PlayerKey.Back) { }

        table.unbind(PlayerKey.Back.asCombo())

        assertFalse(table.isBound(PlayerKey.Back.asCombo()))
        assertFalse(table.handle(PlayerKey.Back))
    }
}
