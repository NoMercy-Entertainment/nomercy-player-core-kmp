// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import tv.nomercy.player.core.player.ActionOptions

// The typed key registry — Kotlin's answer to the web BaseEventMap. Every name
// string is the web key verbatim, because that string is the shared identity
// across the web trio, this library, the docs and the wire. The payload type
// rides on the key, so on(CoreEvents.Time) hands the listener a TimeUpdate with
// no cast and no event-map generic.
//
// Still a subset of the ~150 the full map has: what is here is what the core
// controllers actually emit. The rest land with the features that emit them,
// and the plan's appendix holds the authoritative name list.
//
// Only v2 names appear. The v1 aliases (current, finished, qualityLevels) are
// never registered — they are a compatibility layer the web trio owns.
public object CoreEvents {

    // Transport
    public val Play: EventKey<PlaySource> = EventKey("play")
    public val Pause: EventKey<PlaySource> = EventKey("pause")
    public val Stop: EventKey<PlaySource> = EventKey("stop")
    public val Next: EventKey<PlaySource> = EventKey("next")
    public val Previous: EventKey<PlaySource> = EventKey("previous")
    public val Ended: EventKey<Unit> = EventKey("ended")
    public val Phase: EventKey<PhaseChange> = EventKey("phase")
    public val Time: EventKey<TimeUpdate> = EventKey("time")
    public val Seek: EventKey<SeekPosition> = EventKey("seek")
    public val Seeked: EventKey<SeekPosition> = EventKey("seeked")

    // The cancellable half of every transport action. A listener that vetoes
    // one gets told through the matching *Prevented event, so a chrome can
    // explain why the button did nothing.
    public val BeforePlay: EventKey<BeforeEvent<ActionOptions>> = EventKey("beforePlay")
    public val BeforePause: EventKey<BeforeEvent<ActionOptions>> = EventKey("beforePause")
    public val BeforeStop: EventKey<BeforeEvent<ActionOptions>> = EventKey("beforeStop")
    public val BeforeNext: EventKey<BeforeEvent<ActionOptions>> = EventKey("beforeNext")
    public val BeforePrevious: EventKey<BeforeEvent<ActionOptions>> = EventKey("beforePrevious")
    public val BeforeSeek: EventKey<BeforeEvent<SeekPosition>> = EventKey("beforeSeek")

    public val PlayPrevented: EventKey<PreventedAction> = EventKey("playPrevented")
    public val PausePrevented: EventKey<PreventedAction> = EventKey("pausePrevented")
    public val StopPrevented: EventKey<PreventedAction> = EventKey("stopPrevented")
    public val NextPrevented: EventKey<PreventedAction> = EventKey("nextPrevented")
    public val PreviousPrevented: EventKey<PreventedAction> = EventKey("previousPrevented")
    public val SeekPrevented: EventKey<PreventedAction> = EventKey("seekPrevented")

    // Volume
    public val Volume: EventKey<VolumeChange> = EventKey("volume")
    public val BeforeVolume: EventKey<BeforeEvent<VolumeChange>> = EventKey("beforeVolume")
    public val VolumePrevented: EventKey<PreventedAction> = EventKey("volumePrevented")
    public val Mute: EventKey<MuteChange> = EventKey("mute")
    public val BeforeMute: EventKey<BeforeEvent<MuteChange>> = EventKey("beforeMute")
    public val MutePrevented: EventKey<PreventedAction> = EventKey("mutePrevented")

    // Cursor and queue
    public val Item: EventKey<ItemChange> = EventKey("item")
    public val Queue: EventKey<QueueChange> = EventKey("queue")
    public val QueueAppend: EventKey<tv.nomercy.player.core.events.QueueAppend> = EventKey("queue:append")
    public val QueuePrepend: EventKey<tv.nomercy.player.core.events.QueuePrepend> = EventKey("queue:prepend")
    public val QueueInsert: EventKey<tv.nomercy.player.core.events.QueueInsert> = EventKey("queue:insert")
    public val QueueRemove: EventKey<tv.nomercy.player.core.events.QueueRemove> = EventKey("queue:remove")
    public val QueueMove: EventKey<tv.nomercy.player.core.events.QueueMove> = EventKey("queue:move")
    public val QueueClear: EventKey<tv.nomercy.player.core.events.QueueClear> = EventKey("queue:clear")
    public val QueueShuffle: EventKey<QueueChange> = EventKey("queue:shuffle")
    public val QueueSort: EventKey<QueueChange> = EventKey("queue:sort")

    // Nothing left to play and repeat is off. Distinct from ended, which is
    // about one item finishing.
    public val QueueExhausted: EventKey<Unit> = EventKey("queue:exhausted")

    // Backlog — what has already played, so a back button still works after a
    // shuffle reordered the queue underneath it.
    public val Backlog: EventKey<QueueChange> = EventKey("backlog")
    public val BacklogAppend: EventKey<tv.nomercy.player.core.events.QueueAppend> = EventKey("backlog:append")
    public val BacklogRemove: EventKey<tv.nomercy.player.core.events.QueueRemove> = EventKey("backlog:remove")
    public val BacklogClear: EventKey<tv.nomercy.player.core.events.QueueClear> = EventKey("backlog:clear")

    // The property and the payload class deliberately share a spelling: Kotlin
    // resolves EventKey<StreamError> in the type namespace and the property in
    // the value namespace, so both read as the event they belong to.
    public val StreamError: EventKey<StreamError> = EventKey("stream:error")

    public val PlaybackRate: EventKey<RateChange> = EventKey("playbackRate")
    public val BeforePlaybackRate: EventKey<BeforeEvent<RateChange>> = EventKey("beforePlaybackRate")
    public val PlaybackRatePrevented: EventKey<PreventedAction> = EventKey("playbackRatePrevented")

    // What the engine reports, separate from what was asked for. A backend that
    // silently clamps a rate it cannot do says so here.
    public val BackendRateChange: EventKey<RateChange> = EventKey("backend:ratechange")

    public val Repeat: EventKey<RepeatChange> = EventKey("repeat")
    public val BeforeRepeat: EventKey<BeforeEvent<RepeatChange>> = EventKey("beforeRepeat")
    public val RepeatPrevented: EventKey<PreventedAction> = EventKey("repeatPrevented")

    public val Shuffle: EventKey<ShuffleChange> = EventKey("shuffle")
    public val BeforeShuffle: EventKey<BeforeEvent<ShuffleChange>> = EventKey("beforeShuffle")
    public val ShufflePrevented: EventKey<PreventedAction> = EventKey("shufflePrevented")

    public val ItemEndingSoon: EventKey<tv.nomercy.player.core.events.ItemEndingSoon> =
        EventKey("itemEndingSoon")

    public val Duration: EventKey<Double> = EventKey("duration")

    public val BeforeSetup: EventKey<BeforeEvent<Unit>> = EventKey("beforeSetup")
    public val Ready: EventKey<Unit> = EventKey("ready")
    public val BeforeDispose: EventKey<BeforeEvent<Unit>> = EventKey("beforeDispose")
    public val DisposePrevented: EventKey<PreventedAction> = EventKey("disposePrevented")
    public val Dispose: EventKey<Unit> = EventKey("dispose")
}
