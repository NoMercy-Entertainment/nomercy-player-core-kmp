// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

// Turns an item's url into one the backend can actually fetch.
//
// A NoMercy media server signs playback urls, so the url on a queue item is not
// the url that plays. Doing it here rather than in every loading path means a
// per-library loader that forgets about auth does not exist.
//
// Open with a pass-through default, so a consumer whose media needs no
// authorisation writes nothing and a consumer with a different scheme overrides
// one method.
public open class AuthController {
    public open fun transformUrl(url: String): String = url

    // Get a fresh token, when the one held has stopped working.
    //
    // Suspending and returning nothing: the controller holds whatever it needs
    // and transformUrl reads it afterwards. Handing a token back through here
    // would put it in a caller's hands for no reason, and the reason there is no
    // getter for one at all is the same.
    //
    // Succeeds by default. A consumer whose media needs no authorisation has no
    // token to refresh, and making them implement a method to say so would be a
    // requirement invented by the library.
    public open suspend fun refresh(): Boolean = true
}
