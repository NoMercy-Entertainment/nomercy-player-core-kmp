// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForLanguageCode

// currentLocale rather than a captured one, so a viewer who changes the system
// language sees the menus follow without relaunching.
//
// localizedStringForLanguageCode answers null for a code it does not recognise,
// which is exactly where the contract says to fall back to the tag itself.
public actual fun displayLanguage(tag: String): String {
    if (tag.isBlank()) return tag

    return NSLocale.currentLocale.localizedStringForLanguageCode(tag) ?: tag
}
