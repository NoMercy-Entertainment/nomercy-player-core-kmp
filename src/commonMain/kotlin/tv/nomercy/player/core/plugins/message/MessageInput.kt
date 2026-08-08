// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.message

import kotlin.jvm.JvmInline

/**
 * What the message plugin was asked to show.
 *
 * A bare string is the common case and stays one call. The full form carries a
 * duration, because a message a consumer wants on screen for eight seconds and
 * one it wants for one are the same call with a different number, not two APIs.
 */
public sealed interface MessageInput {

    public val text: String

    @JvmInline
    public value class Text(public override val text: String) : MessageInput

    public data class Timed(
        public override val text: String,
        public val durationMs: Long,
    ) : MessageInput
}
