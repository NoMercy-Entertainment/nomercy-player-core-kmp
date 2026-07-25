// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Every state enum in the player carries the web wire string alongside its
// Kotlin name, because the two deliberately differ where the wire string would
// make a poor constant (SetupState.SETTING_UP is the token "setup"). Anything
// that serialises player state reads this, not the enum name.
public interface TokenEnum {
    public val token: String
}

// Shared by every state enum's fromToken. Written once so the failure message
// is worded the same everywhere and cannot drift enum by enum.
internal fun <T : TokenEnum> List<T>.byToken(token: String, typeName: String): T =
    firstOrNull { it.token == token }
        ?: throw IllegalArgumentException("unknown $typeName token: $token")
