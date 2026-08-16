// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Waits out a segment that has been promised but not yet written.
 *
 * A live-transcode playlist lists the whole timeline while the encoder is still
 * working through it, so a 404 on a segment means "not yet", not "never". The
 * default policy gives that three tries inside about three seconds and then
 * fails playback — which is why an encoder starting cold, or catching up after
 * a seek, killed the stream on a file that was about to be perfectly playable.
 *
 * The web survives the same server for the same reason: hls.js does not retry a
 * 4xx either, and the backend's own fatal-network handler restarts loading
 * three times over seven seconds behind it. This is that handler's peer, one
 * layer lower, where Media3 puts the decision.
 *
 * Only 404 is treated this way, and only its schedule changes. Every other
 * error keeps the default's count and delay exactly.
 */
@OptIn(UnstableApi::class)
internal class PendingSegmentRetryPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = PENDING_SEGMENT_RETRIES

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = when {
        isPending(loadErrorInfo) ->
            (loadErrorInfo.errorCount * RETRY_STEP_MS).coerceAtMost(RETRY_CAP_MS)
        // The raised count above is for pending segments alone. Everything else
        // is held to the default's own limit for its data type, so a genuinely
        // missing file still fails when it used to.
        loadErrorInfo.errorCount > super.getMinimumLoadableRetryCount(loadErrorInfo.mediaLoadData.dataType) ->
            C.TIME_UNSET
        else -> super.getRetryDelayMsFor(loadErrorInfo)
    }

    /**
     * Never blacklist a variant over a pending segment.
     *
     * The default excludes the track for a minute on a 404, which for a live
     * session's single rendition means excluding the only thing there is to
     * play. Returning null sends it down the retry path instead.
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        if (isPending(loadErrorInfo)) null else super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
}

private fun isPending(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
    val error: InvalidResponseCodeException = loadErrorInfo.exception as? InvalidResponseCodeException ?: return false
    return error.responseCode == NOT_WRITTEN_YET
}

private const val NOT_WRITTEN_YET = 404

// Roughly a minute of waiting, spent mostly at the cap: 1s, 2s, then 3s apart.
// Long enough for an encoder to reach the requested position from cold, short
// enough that a server that will never answer still fails.
private const val PENDING_SEGMENT_RETRIES = 20
private const val RETRY_STEP_MS = 1_000L
private const val RETRY_CAP_MS = 3_000L
