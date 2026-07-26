// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.res.Configuration
import android.content.res.Resources

// Android TV or Android phone, from the system configuration rather than a
// Context.
//
// Resources.getSystem() is deliberate. Every other way to ask this — a
// UiModeManager, a PackageManager feature check — needs a Context, and a
// library that demanded one to answer "is this a television" would push that
// requirement into every consumer's construction path, including the ones that
// only want to know how to lay out a button.
//
// The system configuration carries the device's UI mode, and on a television
// that is what the platform itself set. It is the same value the framework uses
// to pick between res/values and res/values-television.
private val DETECTED: Device by lazy {
    Device(formFactor = detectFormFactor(), os = OperatingSystem.ANDROID)
}

public actual fun currentDevice(): Device = DETECTED

private fun detectFormFactor(): FormFactor = formFactorFor(Resources.getSystem().configuration.uiMode)

// Split from the lookup so the classification can be tested without a device.
//
// The mask is the whole reason this is its own function. Configuration.uiMode
// packs night mode into the same int, so comparing the raw field would give
// every phone in dark mode the ten-foot chrome — a bug that only appears after
// sunset and only for some users.
internal fun formFactorFor(uiMode: Int): FormFactor {
    // Everything that is not a television is MOBILE: a tablet and a phone want
    // the same chrome, and Android does not run this player on a desktop.
    val type: Int = uiMode and Configuration.UI_MODE_TYPE_MASK
    return if (type == Configuration.UI_MODE_TYPE_TELEVISION) FormFactor.TV else FormFactor.MOBILE
}
