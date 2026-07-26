// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.util.Base64
import java.io.File

// The shared clip on disk, where Media3 can open it.
//
// The bytes live in commonTest so the Apple gate reads the same ones; this is
// only the Android way of getting them onto the filesystem.
internal fun writeDualAudioClip(target: File): File {
    target.writeBytes(Base64.decode(DUAL_AUDIO_CLIP_BASE64, Base64.DEFAULT))
    return target
}
