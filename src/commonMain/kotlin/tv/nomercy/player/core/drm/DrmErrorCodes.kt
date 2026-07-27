// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// Why protected content would not play.
//
// Separate codes rather than one failure, because the answers a viewer needs are
// different and only one of them is worth showing them: a scheme this device
// cannot do is a reason to offer another version, a key that would not fetch is
// worth retrying, and a licence the server refused is an account problem.
public object DrmErrorCodes {
    public const val UNSUPPORTED_SCHEME: String = "core:drm/unsupported-scheme"
    public const val KEY_REQUEST_FAILED: String = "core:drm/key-request-failed"
    public const val LICENSE_REQUEST_FAILED: String = "core:drm/license-request-failed"
    public const val LICENSE_REFUSED: String = "core:drm/license-refused"
    public const val OUTPUT_NOT_PROTECTED: String = "core:drm/output-not-protected"
    public const val NO_LICENSE_URL: String = "core:drm/no-license-url"
}
