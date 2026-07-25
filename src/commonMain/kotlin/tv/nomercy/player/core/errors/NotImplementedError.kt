// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The method is in the contract, but this backend or library does not do it —
// asking a music player for subtitles, or a backend without a quality ladder
// to switch level. Not a bug; the honest answer to a valid call.
//
// The name shadows kotlin.NotImplementedError, which TODO() throws. That is
// deliberate contract parity with the web trio, and the two are genuinely
// different: TODO() means unwritten, this means unsupported. Catch this one by
// its qualified name if both are in scope.
//
// [feature] defaults rather than being optional so the code is always a
// parseable namespace:category/reason. The web permits a bare
// core:not-implemented; a code that sometimes parses and sometimes does not is
// worse than a slightly longer one.
public open class NotImplementedError(
    message: String,
    feature: String = "unknown",
) : PlayerError(
    code = "core:not-implemented/$feature",
    scope = ErrorScope.core(),
    severity = Severity.ERROR,
    message = message,
)
