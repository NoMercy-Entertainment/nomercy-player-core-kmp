// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ExoPlayerVideoBackend.release() used to never cancel the scope it built
// for itself — on the real Android path (PlatformVideoEngines.android.kt
// passes no scope), every released backend left its SupervisorJob, and every
// in-flight fireAndForget coroutine closing over the released ExoPlayer,
// reachable for the rest of the process's life.
class ReleaseOwnedScopeTest {

    @Test
    fun anOwnedScopeIsCancelledOnRelease() {
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        releaseOwnedScope(owned, owns = true)

        assertFalse(owned.isActive, "a scope this backend built for itself must be cancelled on release")
    }

    @Test
    fun aCallerSuppliedScopeIsNeverCancelled() {
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        releaseOwnedScope(callers, owns = false)

        assertTrue(callers.isActive, "a scope the caller supplied must survive this backend's release")
    }
}
