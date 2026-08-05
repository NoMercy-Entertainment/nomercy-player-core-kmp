// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

// The visualiser's own namespace, which is the web's too. A renderer is written
// by whoever wants the effect, so its failure belongs to the visualiser rather
// than to the engine that handed it a frame.
public object VisualizationErrorCodes {
    public const val RENDER_FAILED: String = "visualization:render/failed"

    public val all: Set<String> = setOf(RENDER_FAILED)
}
