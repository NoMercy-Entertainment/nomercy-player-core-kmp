// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// Handing a studio scheme to the platform's own key machinery.
//
// The seams exist and compile; neither of them pretends to complete a handshake
// it cannot. The server has key storage and encoder building blocks and no
// licence endpoint, and FairPlay additionally needs a certificate from Apple
// that has not been issued. A binding that built a request against a URL nobody
// serves would look finished in a review and fail on every device.
//
// So a blocked binding says which thing is missing. That is the difference
// between a feature waiting on one known dependency and a feature nobody can
// account for.
public interface StudioDrmBinding {

    public fun prepare(config: DrmConfig): DrmBindingResult
}

public sealed interface DrmBindingResult {

    public data object Ready : DrmBindingResult

    public data class Blocked(val code: String, val reason: String) : DrmBindingResult
}

// A binding cannot start without somewhere to send the challenge. Checked here
// rather than in each platform's binding, because the answer is the same on all
// of them and one copy is one thing to correct when the endpoint ships.
internal fun licenseUrlOrBlocked(config: DrmConfig): DrmBindingResult.Blocked? {
    val url: String? = config.licenseUrl
    if (url.isNullOrBlank()) {
        return DrmBindingResult.Blocked(
            DrmErrorCodes.LICENSE_URL_MISSING,
            "no licence endpoint for ${config.scheme}",
        )
    }

    return null
}
