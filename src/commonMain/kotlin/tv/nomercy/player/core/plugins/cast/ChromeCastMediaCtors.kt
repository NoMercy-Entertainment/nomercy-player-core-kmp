// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * The receiver framework's own constructors, handed in rather than reached for.
 *
 * The web takes these because the Chromecast SDK arrives as a global on a page
 * it does not control. The native senders take them because the framework is a
 * platform SDK this module must not link — core has no Play Services dependency
 * and adding one would put it in every consumer's APK, cast or no cast.
 *
 * Either way the plugin holds a seam rather than a dependency, which is also
 * what lets a test drive it with fakes.
 */
public interface ChromeCastMediaCtors {
    public fun mediaInfo(contentId: String, contentType: String): CastMediaInfo
    public fun loadRequest(mediaInfo: CastMediaInfo): Map<String, Any?>
    public fun genericMediaMetadata(): CastMediaMetadata
    public fun streamType(type: CastStreamType): String
}
