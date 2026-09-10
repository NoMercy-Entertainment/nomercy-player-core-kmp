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
 *
 * [allowPendingSegments] exists because "not yet" is a video idea. Music never
 * live-transcodes — every track is a direct-play static file — so a 404 there
 * always means "there is no such file", never "the encoder hasn't reached it".
 * Applying the video schedule anyway held a real, permanently-missing music
 * file's failure back for the full ~57 seconds (20 tries, 1s/2s/3s.../3s) before
 * the app ever heard about it — measured live, real phone, 2026-09-08: the bar
 * froze for almost a minute on a genuinely absent file before recovery could
 * even start. [tv.nomercy.player.core.ports.buildEngine] passes false for the
 * audio engine and leaves video's leniency exactly as it was.
 */
@OptIn(UnstableApi::class)
internal class PendingSegmentRetryPolicy(
    private val allowPendingSegments: Boolean = true,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (PendingSegment.appliesTo(dataType, allowPendingSegments)) {
            PENDING_SEGMENT_RETRIES
        } else {
            super.getMinimumLoadableRetryCount(dataType)
        }

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

    private fun isPending(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
        val error: InvalidResponseCodeException = loadErrorInfo.exception as? InvalidResponseCodeException
            ?: return false
        return PendingSegment.isPending(loadErrorInfo.mediaLoadData.dataType, error.responseCode, allowPendingSegments)
    }
}

/**
 * Which 404 means "not written yet" and which means "there is no such item".
 *
 * A live-transcode SEGMENT that answers 404 is a promise the encoder has not
 * reached, and waiting a minute for it is right. The PLAYLIST answering 404 is
 * the server saying it has no media for this item at all, and that answer will
 * not change — but the wait applied to both, so an episode nobody had encoded
 * held the viewer on a spinner for the full minute before failing. Stoney, on
 * exactly that episode: "this video is not encoded yet and should throw a 404
 * and skip to the next".
 *
 * [allowPendingSegments] narrows it further, for the engine that never has a
 * live-transcode concept in the first place — see [PendingSegmentRetryPolicy]'s
 * own doc.
 *
 * Split out of the policy so the rule can be checked without a device:
 * [androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo]
 * cannot be built on a host JVM, which is how a retry schedule ends up
 * unverified and one media type quietly inherits the other's patience.
 */
internal object PendingSegment {

    /** Only media segments wait, and only where waiting can mean anything. */
    fun appliesTo(dataType: Int, allowPendingSegments: Boolean = true): Boolean =
        allowPendingSegments && dataType == C.DATA_TYPE_MEDIA

    fun isPending(dataType: Int, responseCode: Int, allowPendingSegments: Boolean = true): Boolean =
        appliesTo(dataType, allowPendingSegments) && responseCode == NOT_WRITTEN_YET

    private const val NOT_WRITTEN_YET = 404
}

// Roughly a minute of waiting, spent mostly at the cap: 1s, 2s, then 3s apart.
// Long enough for an encoder to reach the requested position from cold, short
// enough that a server that will never answer still fails.
private const val PENDING_SEGMENT_RETRIES = 20
private const val RETRY_STEP_MS = 1_000L
private const val RETRY_CAP_MS = 3_000L
