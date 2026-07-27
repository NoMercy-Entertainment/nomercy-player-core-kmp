// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// FairPlay on Apple platforms, which needs two things we do not have.
//
// A licence endpoint, like Widevine, and additionally a streaming certificate
// issued by Apple to the deployment. The certificate is not a build detail: it
// is applied for, granted to an organisation, and without it the key session
// cannot even open. Both blockers are reported by name because they clear
// independently and on different timescales.
public class FairPlayBinding : StudioDrmBinding {

    override fun prepare(config: DrmConfig): DrmBindingResult {
        if (config.scheme != DrmScheme.FAIRPLAY) {
            return DrmBindingResult.Blocked(
                DrmErrorCodes.UNSUPPORTED_SCHEME,
                "this binding runs ${DrmScheme.FAIRPLAY} and was given ${config.scheme}",
            )
        }

        if (config.certificate == null) {
            return DrmBindingResult.Blocked(
                DrmErrorCodes.LICENSE_REQUEST_FAILED,
                "no FairPlay streaming certificate has been issued for this deployment",
            )
        }

        return licenseUrlOrBlocked(config) ?: DrmBindingResult.Ready
    }
}

// AES-128 needs no key box, and FairPlay is the Apple platforms' own scheme.
// Widevine is not available to an AVPlayer, so a stream packaged for Android is
// one an iPhone should be told about early rather than at the first key.
public class AvPlayerDrmCapability : DrmCapability {

    override fun supports(scheme: DrmScheme): Boolean =
        scheme == DrmScheme.AES_128 || scheme == DrmScheme.FAIRPLAY || scheme == DrmScheme.CLEAR
}
