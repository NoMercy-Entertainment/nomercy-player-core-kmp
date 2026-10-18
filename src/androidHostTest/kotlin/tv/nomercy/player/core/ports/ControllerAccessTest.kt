// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerAccessTest {

    private val own = "tv.nomercy.app"

    @Test
    fun aTrustedSystemControllerIsAccepted() {
        assertTrue(ControllerAccess.allows(isTrusted = true, controllerPackage = "android", ownPackage = own))
    }

    @Test
    fun anUnknownAppThatIsNotTrustedIsRejected() {
        assertFalse(ControllerAccess.allows(isTrusted = false, controllerPackage = "com.example.other", ownPackage = own))
    }

    @Test
    fun theAppItselfIsAcceptedEvenWhenTheSystemDoesNotCallItTrusted() {
        assertTrue(ControllerAccess.allows(isTrusted = false, controllerPackage = own, ownPackage = own))
    }

    @Test
    fun theCarDisplayIsAcceptedByPackage() {
        assertTrue(
            ControllerAccess.allows(
                isTrusted = false,
                controllerPackage = "com.google.android.projection.gearhead",
                ownPackage = own,
            ),
        )
    }

    @Test
    fun aLookalikePackageIsNotTheCarDisplay() {
        assertFalse(
            ControllerAccess.allows(
                isTrusted = false,
                controllerPackage = "com.google.android.projection.gearhead.evil",
                ownPackage = own,
            ),
        )
    }
}
