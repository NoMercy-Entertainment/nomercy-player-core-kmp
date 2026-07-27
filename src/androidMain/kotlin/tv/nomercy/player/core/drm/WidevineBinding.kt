// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// Widevine on Android, which is the one studio scheme with everything present
// on the device already: the key box is in the platform and the engine knows how
// to talk to it.
//
// What is missing is on our side. There is no licence route on the server, so
// this reports which dependency it is waiting for rather than building a request
// against a URL nobody serves.
public class WidevineBinding : StudioDrmBinding {

    override fun prepare(config: DrmConfig): DrmBindingResult {
        if (config.scheme != DrmScheme.WIDEVINE) {
            return DrmBindingResult.Blocked(
                DrmErrorCodes.UNSUPPORTED_SCHEME,
                "this binding runs ${DrmScheme.WIDEVINE} and was given ${config.scheme}",
            )
        }

        return licenseUrlOrBlocked(config) ?: DrmBindingResult.Ready
    }
}

// What this engine can decrypt.
//
// AES-128 needs no key box at all, and Widevine is in the platform. The others
// are not: a PlayReady stream on Android is a stream this device cannot play,
// and saying so early is what lets a chrome offer a different version.
public class ExoPlayerDrmCapability : DrmCapability {

    override fun supports(scheme: DrmScheme): Boolean =
        scheme == DrmScheme.AES_128 || scheme == DrmScheme.WIDEVINE || scheme == DrmScheme.CLEAR
}
