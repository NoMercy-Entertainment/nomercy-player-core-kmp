// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// How a stream is protected.
//
// The identifier is what the browser's encrypted-media API calls the system, and
// two of these have none: AES-128 is an HLS feature with a key in the manifest
// and no key system at all, and clear content is not protected. Carrying the
// string here rather than at each engine is what keeps one spelling of
// "com.widevine.alpha" in the library instead of one per backend.
public enum class DrmScheme(public val keySystem: String?) {
    AES_128(null),
    WIDEVINE("com.widevine.alpha"),
    FAIRPLAY("com.apple.fps"),
    PLAYREADY("com.microsoft.playready"),
    CLEAR(null),
}

// What the output has to guarantee before the content will play.
public enum class HdcpLevel(public val wireValue: String) {
    TYPE_0("type-0"),
    TYPE_1("type-1"),
    NONE("none"),
}

// What this library is asked to play protected content with.
//
// Not a data class, deliberately. The certificate is bytes, and array equality
// is identity: two configurations built from the same certificate would compare
// unequal, which turns any "has this changed" check into a reload on every
// frame that mentions it.
public class DrmConfig(
    public val scheme: DrmScheme,

    // Null for AES-128, which has no licence server. The web contract requires
    // it because it only describes studio systems; this one covers the scheme
    // that is realistic today as well.
    public val licenseUrl: String? = null,

    // FairPlay's service certificate. Optional for the others, which fetch
    // theirs as part of the exchange.
    public val certificate: ByteArray? = null,

    public val hdcpRequired: HdcpLevel? = null,

    // The three hooks a consumer needs to sign or reshape a licence exchange
    // without subclassing anything. They exist because licence servers disagree
    // about everything except that they want a challenge and return a licence:
    // one wants an HMAC header, another wraps the body in its own envelope, and
    // a library that hard-coded either would be unusable with the other.
    public val signRequest: (suspend (DrmLicenseExchange) -> DrmLicenseExchange)? = null,
    public val transformLicenseRequest: (suspend (ByteArray) -> ByteArray)? = null,
    public val transformLicenseResponse: (suspend (ByteArray) -> ByteArray)? = null,
)

// One side of a licence exchange, so a signer can change the headers as well as
// the body. A hook given only bytes cannot add the authorisation header that is
// the most common thing a licence server asks for.
public class DrmLicenseExchange(
    public val body: ByteArray,
    public val headers: Map<String, String> = emptyMap(),
)
