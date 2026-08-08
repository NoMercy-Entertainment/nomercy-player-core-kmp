// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import tv.nomercy.player.core.ports.VideoBackend

/**
 * One desktop playback engine, and whether this machine can run it.
 *
 * The desktop had exactly one engine and it was constructed by name at the call
 * site, so swapping it meant editing every caller and running two engines side
 * by side was not expressible at all. libmpv is arriving to replace libVLC and
 * has to be provable against the same contract on the same machine before
 * anything is deleted — which is a registry of engines, not a rename.
 *
 * [isAvailable] never throws and never installs anything. A machine with no
 * payload for an engine is an ordinary machine, and the selection has to be able
 * to ask before it commits to one.
 */
public interface VideoEngineProvider {

    /** Stable, lowercase, and what a consumer names in configuration. */
    public val id: String

    /** Whether [create] would succeed here. */
    public fun isAvailable(): Boolean

    /**
     * Why not, in a sentence a developer can act on, or null when it is
     * available. The reason is separate from the boolean because "no build
     * exists for this host" and "the payload failed to install" are different
     * problems and only one of them is a fault.
     */
    public fun whyUnavailable(): String?

    /**
     * Whether this engine can decode a stream declaring [codec], as an RFC 6381
     * string like `avc1.6E0028`.
     *
     * Defaulted to yes, and that default is right for every software decoder:
     * ffmpeg inside libmpv opens what the file contains rather than what the
     * hardware offers. It is ExoPlayer that has to answer honestly, because a
     * device's decoders are a fixed list and a file outside it does not play at
     * all — no error a user can act on, just a black picture.
     *
     * Null [codec] means nobody knows yet, which is a yes: refusing an engine
     * because a manifest was silent would ground the default engine on every
     * stream that does not declare one.
     */
    public fun canDecode(codec: String?): Boolean = true

    public fun create(): VideoBackend
}
