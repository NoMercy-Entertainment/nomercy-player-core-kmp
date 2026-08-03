// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Where the player writes what it is doing. The host supplies one; the player
// never picks a logging framework for anybody.
//
// [child] is what makes a log line attributable: a plugin gets
// rootLogger.child(its id), so its output reads [nmplayer][lyrics] and a
// support ticket says which plugin was talking.
public interface Logger {
    public fun error(message: String, vararg args: Any?)
    public fun warn(message: String, vararg args: Any?)
    public fun info(message: String, vararg args: Any?)
    public fun debug(message: String, vararg args: Any?)

    // The level below debug: per-frame, per-segment, per-cue detail that is
    // noise in a bug report and the whole story in a decode investigation.
    //
    // Defaulted to debug rather than added to every implementer's required
    // surface, and defaulted to debug rather than to nothing. A no-op would
    // silently drop these lines for every Logger a consumer already wrote,
    // which is the failure mode where a level exists, a library writes to it,
    // and the person reading the log never learns it was there.
    public fun trace(message: String, vararg args: Any?): Unit = debug(message, *args)

    public fun child(scope: String): Logger
}
