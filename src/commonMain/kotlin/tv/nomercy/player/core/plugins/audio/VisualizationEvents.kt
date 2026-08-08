// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

/** What a visualiser reports. */
public sealed interface VisualizationEvents {

    /**
     * Emitted once, when there is no audio graph to visualise.
     *
     * A reason rather than a bare flag, because "unsupported" covers a device
     * with no audio context, a track that never opened, and a graph the host
     * declined to build — and a chrome showing "visualisation unavailable" with
     * no cause sends the person to the wrong place.
     */
    public data class Unsupported(val reason: String) : VisualizationEvents

    /** Emitted after each render. */
    public data class Rendered(val frame: VisualizationFrame) : VisualizationEvents
}
