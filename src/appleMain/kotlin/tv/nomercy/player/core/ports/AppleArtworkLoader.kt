// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.MediaPlayer.MPMediaItemArtwork
import platform.UIKit.UIImage
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The cover the lock screen draws, fetched.
//
// Every other platform takes a URL: Media3 sets artworkUri, SMTC and MPRIS are
// handed the string. Apple is alone in wanting pixels — MPMediaItemArtwork is
// built around a request handler that returns a UIImage — so this is the one
// place a transport has to do the download itself, and why it did not exist:
// the field was populated on all four platforms and read on three.
//
// Off the main thread to fetch and back onto it to hand over, because the now
// playing centre is main-thread only and NSURLSession answers on a background
// queue. A caller that forgot the hop would work in a simulator and fail on a
// device under load, which is the worst shape a threading bug can take.
@OptIn(ExperimentalForeignApi::class)
internal class AppleArtworkLoader(
    private val fetch: (NSURL, (NSData?) -> Unit) -> Unit = ::fetchOverNetwork,
) {

    // Delivers nothing rather than something wrong. A cover that fails to load
    // leaves the previous announcement's text on screen, which is what a viewer
    // wants; blanking the item because its image 404'd is strictly worse than
    // showing the title with no picture.
    fun load(url: String, onReady: (MPMediaItemArtwork) -> Unit) {
        val parsed: NSURL = NSURL.URLWithString(url) ?: return

        fetch(parsed) { data ->
            val image: UIImage? = data?.let(UIImage::imageWithData)

            if (image != null) {
                dispatch_async(dispatch_get_main_queue()) { onReady(artworkOf(image)) }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun artworkOf(image: UIImage): MPMediaItemArtwork =
    MPMediaItemArtwork(boundsSize = image.size) { _ -> image }

@OptIn(ExperimentalForeignApi::class)
private fun fetchOverNetwork(url: NSURL, done: (NSData?) -> Unit) {
    NSURLSession.sharedSession
        .dataTaskWithURL(url) { data, _, _ -> done(data) }
        .resume()
}
