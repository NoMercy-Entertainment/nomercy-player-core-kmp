// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

// What this device says about itself, handed to the arithmetic that decides.
//
// Only the lookup lives here. Everything that turns these numbers into a config
// is in commonMain, where it can be tested without a device.
public fun bufferConfigForDevice(context: Context): BufferConfig {
    val activity: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val isTv: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    // Both figures, because they disagree and the disagreement matters.
    // memoryClass is the per-app heap the system promises; the runtime maximum
    // is what this process actually got, which is larger when the app asked for
    // a large heap. The large class is the ceiling either way.
    val runtimeMaxMb: Int = (Runtime.getRuntime().maxMemory() / BYTES_PER_MB).toInt()
    val availableMb: Int = maxOf(activity.memoryClass, runtimeMaxMb)
        .coerceAtMost(activity.largeMemoryClass)

    return BufferBudget.forMemory(
        availableMb = availableMb,
        isTv = isTv,
        isLowRam = activity.isLowRamDevice,
    )
}
