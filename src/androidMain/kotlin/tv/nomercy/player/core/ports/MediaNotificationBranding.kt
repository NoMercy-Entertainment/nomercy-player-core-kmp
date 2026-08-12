// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What a media notification looks like — an app concern, not a library one.
//
// This library builds the session and drives Media3's own notification
// machinery, but the icon in the status bar and the words in the channel
// list are the consuming app's branding, not this library's to invent or
// ship. The library ships zero drawables and zero strings for this; a
// consumer that wants its own icon and a favorite button supplies both here,
// once, and gets both back on every notification this library ever builds.
// A consumer that supplies nothing gets Media3's own stock notification —
// correct, generic, and exactly what shipped before this type existed.
public data class MediaNotificationBranding(
    val smallIconResId: Int,
    val channelId: String,
    val channelNameResId: Int,
)

// Installed the same way, and read the same way, as the Context
// [PlatformEnvironment] already carries — not a second holder beside it.
// [NoMercyPlaybackService] is a manifest-registered singleton Android builds
// itself with no constructor the app ever calls, exactly the reason a
// context couldn't be threaded to `defaultSystemTransport()` either; this is
// the same forced-static-seam problem PlatformEnvironment already solved
// once, so it gets the same shape rather than a bespoke object next to it.
// An androidMain-only extension is how it stays out of commonMain: every
// other platform's view of PlatformEnvironment is unchanged, the same way
// [PlatformContext]'s own Android actual carries `androidContext` that no
// other platform's actual has.
@Volatile
private var installedMediaNotificationBranding: MediaNotificationBranding? = null

public fun PlatformEnvironment.installMediaNotificationBranding(branding: MediaNotificationBranding) {
    installedMediaNotificationBranding = branding
}

public val PlatformEnvironment.mediaNotificationBranding: MediaNotificationBranding?
    get() = installedMediaNotificationBranding
