// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.UIKit.UIGraphicsBeginImageContext
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import kotlinx.cinterop.cValue
import platform.CoreGraphics.CGSize

// The lock screen against the real MPNowPlayingInfoCenter, with the cover
// download held by hand so its arrival can be ordered against clear().
@OptIn(ExperimentalForeignApi::class)
class AppleSystemTransportTest {

    private fun onePixelPng(): NSData {
        UIGraphicsBeginImageContext(cValue<CGSize> { width = 1.0; height = 1.0 })
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return UIImagePNGRepresentation(image!!)!!
    }

    private fun settle() {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(0.5))
    }

    @Test
    fun aCoverArrivingAfterClearIsDropped() {
        var deliver: ((NSData?) -> Unit)? = null
        val transport = AppleSystemTransport(
            AppleArtworkLoader { _: NSURL, done -> deliver = done },
        )

        transport.setNowPlaying(NowPlaying(title = "Made Up Song", artworkUrl = "https://example.invalid/c.png"))
        transport.clear()
        deliver?.invoke(onePixelPng())
        settle()

        assertNull(MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo)
        transport.release()
    }
}
