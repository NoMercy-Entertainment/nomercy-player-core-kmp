// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.core.ports.VlcjVideoBackend

/**
 * libVLC, the desktop engine everything shipped on until now.
 *
 * The availability question is already answered properly by the backend itself
 * — a bounded probe on a daemon thread, memoized — so this delegates rather than
 * asking a second way. Two answers to "can this machine play video" is one
 * answer too many.
 */
public object VlcVideoEngineProvider : VideoEngineProvider {

    public override val id: String = "vlc"

    public override fun isAvailable(): Boolean = VlcjVideoBackend.isAvailable()

    public override fun whyUnavailable(): String? = VlcjVideoBackend.whyUnavailable()

    public override fun create(): VideoBackend = VlcjVideoBackend()
}
