// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Desktop, always. The JVM target is the Compose Desktop client and the CLI
// tools around it — there is no JVM build of this player that runs on a phone
// or a television, so a form-factor probe here would only ever confirm what the
// target already means.
//
// The operating system is worth asking about: it decides where a subtitle font
// is found and which native libraries load.
private val DETECTED: Device by lazy {
    Device(formFactor = FormFactor.DESKTOP, os = hostOs())
}

public actual fun currentDevice(): Device = DETECTED

private fun hostOs(): OperatingSystem {
    val name: String = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        name.startsWith("mac") || name.startsWith("darwin") -> OperatingSystem.MACOS
        name.startsWith("win") -> OperatingSystem.WINDOWS
        name.contains("nix") || name.contains("nux") || name.contains("aix") -> OperatingSystem.LINUX
        else -> OperatingSystem.UNKNOWN
    }
}
