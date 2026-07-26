// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Whether a rung was chosen or the engine is adapting.
//
// A quality menu showing a committed selection the viewer never made is worse
// than showing none: it invites them to "fix" a setting that was never set, and
// on the engines where pinning is a ceiling rather than a selection it would be
// claiming more than the engine can deliver.
public enum class QualityMode(public val wire: String) {
    AUTO("auto"),
    MANUAL("manual"),
}
