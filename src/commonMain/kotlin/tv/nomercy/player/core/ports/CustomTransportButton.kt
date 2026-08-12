// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One button beside the standard transport controls — favorite, shuffle,
// download, anything an app wants that isn't one of the fixed verbs on
// TransportActions. Named for the shape it has and not for what any one app
// puts in it: this library ships zero concrete buttons, the same way
// MediaNotificationBranding ships zero icons.
//
// [iconKey] is a platform-neutral token the app defines and resolves itself —
// see CustomTransportButtonIconResolver (androidMain) for where the app
// supplies the actual drawable behind each key, the same install-once,
// read-many seam MediaNotificationBranding already uses for the same reason:
// a resource id is an Android concept and this type is common to every
// platform.
//
// [isActive] is the toggle state a button reflects rather than an action of
// its own — a favorite button pressed once shows filled, pressed again shows
// outline. The app owns what "active" means and pushes a new list (with a
// flipped isActive) through setCustomButtons after every press; this library
// only ever draws whatever list it was last given.
public data class CustomTransportButton(
    val id: String,
    val iconKey: String,
    val label: String,
    val isActive: Boolean = false,
    val onPress: () -> Unit,
)
