// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// TV, always. The wasmJs target is cast-web, the CAF Chromecast receiver — a
// 10-foot browser tab running on a television or streaming box, never a
// phone or a desktop build of this player.
private val DETECTED: Device = Device(formFactor = FormFactor.TV, os = OperatingSystem.UNKNOWN)

public actual fun currentDevice(): Device = DETECTED
