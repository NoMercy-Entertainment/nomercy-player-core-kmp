// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import platform.Foundation.NSLocale
import platform.Foundation.localeWithLocaleIdentifier
import platform.Foundation.localizedStringForLanguageCode

// NSLocale carries the same CLDR data the rest of the system reads from, so an
// Apple client names a language the way its Settings app does.
internal actual fun platformLanguageName(bcp47: String, locale: String): String? =
    NSLocale.localeWithLocaleIdentifier(locale)
        .localizedStringForLanguageCode(bcp47)
        ?.takeIf { it.isNotBlank() }
