// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.res.Configuration
import kotlin.test.Test
import kotlin.test.assertEquals

// Android form-factor classification, against the real Configuration constants.
//
// The value read in production is the one Android itself uses to choose between
// res/values and res/values-television, so these are the exact inputs a device
// hands over — not a stand-in for them.
class AndroidDeviceTest {

    @Test
    fun aTelevisionIsATelevision() {
        assertEquals(FormFactor.TV, formFactorFor(Configuration.UI_MODE_TYPE_TELEVISION))
    }

    @Test
    fun aPhoneIsMobile() {
        assertEquals(FormFactor.MOBILE, formFactorFor(Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun darkModeDoesNotTurnAPhoneIntoATelevision() {
        // uiMode packs night mode into the same int. Without the type mask the
        // comparison reads those bits too, and every phone gets ten-foot chrome
        // after sunset.
        val nightPhone: Int = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

        assertEquals(FormFactor.MOBILE, formFactorFor(nightPhone))
    }

    @Test
    fun aTelevisionInDarkModeIsStillATelevision() {
        val nightTv: Int = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES

        assertEquals(FormFactor.TV, formFactorFor(nightTv))
    }

    @Test
    fun aWatchIsNotMistakenForATelevision() {
        // Both are non-phone form factors and both sit in the same field. A
        // watch reaching the TV branch would get a chrome built for a screen
        // three metres away.
        assertEquals(FormFactor.MOBILE, formFactorFor(Configuration.UI_MODE_TYPE_WATCH))
    }

    @Test
    fun aTelevisionPrefersPowerEfficiencyAndADesktopDoesNot() {
        assertEquals(true, Device(FormFactor.TV, OperatingSystem.ANDROID).prefersPowerEfficiency)
        assertEquals(false, Device(FormFactor.DESKTOP, OperatingSystem.LINUX).prefersPowerEfficiency)
    }
}
