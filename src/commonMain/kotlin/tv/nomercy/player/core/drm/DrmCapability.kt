// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// What this engine can actually decrypt.
//
// Asked rather than assumed, because the answer is different on every engine and
// on the same engine across devices: a phone with no hardware-backed key box
// cannot run a studio scheme its neighbour can. The library decides what to do
// about the answer; only the backend knows what it is.
public interface DrmCapability {

    public fun supports(scheme: DrmScheme): Boolean
}

// Nothing protected, which is every engine until one says otherwise.
//
// A real object so a player built without a DRM-aware backend still answers the
// question, and answers it the honest way rather than by claiming support it
// would then fail to deliver at the worst moment.
public object ClearOnly : DrmCapability {

    override fun supports(scheme: DrmScheme): Boolean = scheme == DrmScheme.CLEAR
}
