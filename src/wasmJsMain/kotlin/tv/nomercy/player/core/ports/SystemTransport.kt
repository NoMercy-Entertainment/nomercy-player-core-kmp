// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The Cast sender phone already owns the system-level "what's playing" surface
// (its own MediaSession/MPNowPlayingInfoCenter, driven by the CAF sender SDK,
// mirrored from CastReceiverBridge.sendMediaStatus) — a browser tab has
// nothing of its own to register with (no Media Session API wiring exists
// here yet, and cast-web's window is never in the foreground for a viewer to
// see it from). No-op until a real receiver-side integration is needed.
public actual fun defaultSystemTransport(): SystemTransport = object : SystemTransport {
    override fun setNowPlaying(nowPlaying: NowPlaying) {}
    override fun setPlaybackState(state: TransportPlaybackState, positionMs: Long, playbackRate: Double) {}
    override fun setActionHandlers(actions: TransportActions) {}
    override fun clear() {}
    override fun release() {}
}
