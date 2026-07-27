// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.device.fakes.FakeDeviceCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What a chrome reads before it decides how to draw itself.
//
// The flags matter less than the one thing derived from them: whether controls
// are reached by moving a highlight or by touching where they are. Getting that
// wrong is a television whose buttons cannot be reached at all.
class DeviceCapabilitiesTest {

    @Test
    fun aTelevisionIsDrivenByFocusRatherThanByTouch() {
        val television = FakeDeviceCapabilities(formFactor = FormFactor.Tv)

        assertTrue(television.prefersFocusNavigation)
        assertTrue(television.hasDpad)
        assertFalse(television.hasTouch)
    }

    @Test
    fun everythingElseIsNot() {
        // The other half, and it is the half that breaks: focus navigation on a
        // phone puts a highlight ring on a screen nobody can move it with.
        for (factor in listOf(FormFactor.Phone, FormFactor.Tablet, FormFactor.Desktop)) {
            assertFalse(
                FakeDeviceCapabilities(formFactor = factor).prefersFocusNavigation,
                "$factor asked for focus navigation",
            )
        }
    }

    @Test
    fun focusNavigationIsDerivedRatherThanSetIndependently() {
        // A device that claimed a remote without being a television would
        // otherwise be able to disagree with itself, and four platforms would
        // each get to decide what that meant.
        val phoneWithAController = FakeDeviceCapabilities(formFactor = FormFactor.Phone, hasDpad = true)

        assertTrue(phoneWithAController.hasDpad)
        assertFalse(phoneWithAController.prefersFocusNavigation, "a controller turned a phone into a television")
    }

    @Test
    fun aDesktopIsAPointerAndAKeyboard() {
        val desktop = FakeDeviceCapabilities(formFactor = FormFactor.Desktop)

        assertTrue(desktop.hasPointer)
        assertFalse(desktop.hasDpad)
        assertFalse(desktop.hasTouch)
    }

    @Test
    fun aTelevisionIsAssumedNotToHearItsOwnVolumeKeys() {
        // The remote talks to the panel or the receiver. A volume bar drawn in
        // response to a key that never arrives is a control that does nothing,
        // and that is worse than not offering one.
        assertFalse(FakeDeviceCapabilities(formFactor = FormFactor.Tv).hasHardwareVolumeKeys)
        assertTrue(FakeDeviceCapabilities(formFactor = FormFactor.Phone).hasHardwareVolumeKeys)
    }
}
