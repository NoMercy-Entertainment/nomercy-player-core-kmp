// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import tv.nomercy.player.core.natives.libmpv.LibMpv
import tv.nomercy.player.core.natives.libmpv.MPV_RENDER_API_TYPE_SW
import tv.nomercy.player.core.natives.libmpv.MpvRenderParams
import tv.nomercy.player.core.natives.libmpv.MpvRenderParamType
import tv.nomercy.player.core.natives.libmpv.MpvEndFileReason
import tv.nomercy.player.core.natives.libmpv.MpvEventPump
import tv.nomercy.player.core.natives.libmpv.MpvHandle
import tv.nomercy.player.core.natives.libmpv.property

/**
 * libmpv as a [VideoBackend].
 *
 * Everything here is a property on a string name, which is mpv's whole API and
 * the reason it can answer the one question libVLC 3 cannot: which rendition of
 * an HLS master to play. `edition` takes an assignment, so the quality menu
 * finally selects something instead of building a list nothing could act on.
 *
 * State is POLLED rather than pushed. A poll of six properties on a scheduled
 * executor produces the same canonical event spine, is the same shape as the
 * Android and Apple backends' own timer, and cannot wedge. The cost is a bounded
 * lateness — one poll interval — on `pause` and `ended`, which are also the two
 * the player above re-derives from its own state.
 *
 * Failure is PUSHED, and has to be. A stream that cannot be opened leaves every
 * property at the value it had before the load, so a poll reads a broken stream
 * and a slow one identically and the player above spins on both — which is what
 * an unreachable host actually looked like on screen. mpv states the reason once,
 * in `END_FILE`, so [MpvEventPump] reads that queue and nothing else from it.
 */
@Suppress("TooManyFunctions")
public class MpvVideoBackend internal constructor(
    private val mpv: LibMpv,
) : VideoBackend, FrameSourceBackend {

    public constructor() : this(LibMpv.load())

    private val bus = StringEventBus()

    // Created eagerly, because a backend that cannot start is a backend the
    // caller must hear about at construction rather than at the first play.
    private val handle: MpvHandle = mpv.mpv_create() ?: error("mpv_create returned null")

    // No window, no terminal, no OSD. Constructing an engine must touch no
    // window system: the conformance gate runs headless, and a desktop client
    // attaches its own frame path afterwards.
    init {
        for ((name, value) in HEADLESS_OPTIONS) mpv.mpv_set_option_string(handle, name, value)
        val started: Int = mpv.mpv_initialize(handle)
        require(started >= 0) { "libmpv would not initialise: ${mpv.mpv_error_string(started)}" }
    }

    private val poller: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nomercy-mpv-poll").apply { isDaemon = true }
    }

    // What the last poll saw, so a change can be told from a repeat. Every
    // canonical event is a TRANSITION, and emitting on each poll instead would
    // give a conformance runner a `pause` four times a second.
    private var lastTime: Double = -1.0
    private var lastPaused: Boolean? = null
    private var lastEnded: Boolean = false
    private var lastWaiting: Boolean = false
    private var lastDuration: Double = 0.0
    private var announcedMetadata: Boolean = false
    private var released: Boolean = false

    // The one thing the poll cannot see. Every property a failed open leaves
    // behind is the value it had before the load — duration zero, eof-reached
    // false, no picture — so a stream that is broken and a stream that is slow
    // are the same reading, and the player above spins on both. mpv says which
    // through END_FILE and nowhere else.
    private val events = MpvEventPump(mpv, handle, ::announceEndFile)

    init {
        poller.scheduleAtFixedRate(::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        events.start()
    }

    // An open that failed, told apart from the credits rolling. Only ERROR is an
    // error: STOP is this backend's own `stop()`, QUIT is shutdown, and REDIRECT
    // is mpv resolving a playlist — reporting any of those would put a failure
    // on screen every time an item changed.
    private fun announceEndFile(reason: Int, error: Int) {
        if (released || reason != MpvEndFileReason.ERROR) return
        bus.emit(BackendEvents.STREAM_ERROR, mpv.mpv_error_string(error))
    }

    // ---- loading -----------------------------------------------------------

    override suspend fun load(url: String, opts: LoadOptions) {
        bus.emit(BackendEvents.LOAD_START)

        // Headers before the file, because mpv reads them when it opens the
        // stream and setting them afterwards applies to the next one.
        val headers: String = opts.headers.entries.joinToString(",") { (name, value) -> "$name: $value" }
        mpv.mpv_set_property_string(handle, "http-header-fields", headers)

        // Paused unless asked otherwise, and the start position given to the
        // open rather than seeked to afterwards. A seek issued before the
        // demuxer is ready is dropped, silently, on every engine here.
        mpv.mpv_set_property_string(handle, PAUSE, if (opts.autoplay) NO else YES)
        mpv.mpv_set_property_string(handle, "start", (opts.startPositionMs / MILLIS_PER_SECOND).toString())

        announcedMetadata = false
        lastEnded = false
        mpv.mpv_command(handle, arrayOf("loadfile", url, "replace", null))
    }

    override suspend fun play() {
        mpv.mpv_set_property_string(handle, PAUSE, NO)
    }

    override fun pause() {
        mpv.mpv_set_property_string(handle, PAUSE, YES)
    }

    override fun stop() {
        mpv.mpv_command(handle, arrayOf("stop", null))
    }

    // ---- the playhead ------------------------------------------------------

    override fun currentTime(): Double = number("time-pos") ?: 0.0

    override fun currentTime(seconds: Double) {
        mpv.mpv_command(handle, arrayOf("seek", seconds.toString(), "absolute", null))
    }

    override fun duration(): Double = number(DURATION) ?: 0.0

    // mpv's own scale is 0..100 and this contract's is 0..1. One conversion,
    // here, rather than at every call site.
    override fun volume(): Float = ((number("volume") ?: 0.0) / VOLUME_SCALE).toFloat()

    override fun volume(value: Float) {
        mpv.mpv_set_property_string(handle, "volume", (value.coerceIn(0f, 1f) * VOLUME_SCALE).toString())
    }

    override fun mute() {
        mpv.mpv_set_property_string(handle, "mute", YES)
    }

    override fun unmute() {
        mpv.mpv_set_property_string(handle, "mute", NO)
    }

    /**
     * The absolute timeline position the demuxer has read to.
     *
     * `demuxer-cache-time` is already absolute, which is what this contract
     * asks for, so it is returned rather than added to the playhead. mpv
     * reports no ranges, so the interface's range-walking default would read
     * an empty list as nothing buffered — the same override libVLC and Media3
     * need for the same reason.
     */
    override fun buffered(): Double = number("demuxer-cache-time") ?: currentTime()

    override fun playbackRate(): Double = number("speed") ?: 1.0

    override fun playbackRate(rate: Double) {
        mpv.mpv_set_property_string(handle, "speed", rate.toString())
    }

    /**
     * Replaces mpv's whole `af` (audio filter) chain.
     *
     * The seam [MpvDspGraph] drives: mpv exposes no raw-PCM tap comparable to
     * libVLC's `amem` callback in its public client API, so the shared
     * [tv.nomercy.player.core.ports.PcmEqualiser] this project's EQ math
     * otherwise runs through cannot be wired in here. `af=lavfi=[...]` is the
     * substitute — FFmpeg's own `equalizer` filter implements the identical
     * RBJ-cookbook peaking transfer function [tv.nomercy.player.core.dsp.BiquadPeaking]
     * does, so a band list translates to it without a second set of numbers.
     */
    public fun setAudioFilter(af: String) {
        mpv.mpv_set_property_string(handle, "af", af)
    }

    /**
     * What mpv measures the stream at, in bits per second.
     *
     * `cache-speed` is bytes per second over the last second of reading, and it
     * is zero while the cache is full — which is honest: an engine that is not
     * reading is not measuring, and a stale number would put an adaptation
     * decision on a measurement that never happened.
     */
    override fun bandwidthEstimate(): Int =
        ((number("cache-speed") ?: 0.0) * BITS_PER_BYTE).toInt().coerceAtLeast(0)

    override fun state(): BackendState = when {
        released -> BackendState.IDLE
        flag("eof-reached") -> BackendState.IDLE
        number(DURATION) == null -> BackendState.LOADING
        flag(PAUSE) -> BackendState.PAUSED
        flag("core-idle") -> BackendState.READY
        else -> BackendState.PLAYING
    }

    // ---- tracks ------------------------------------------------------------

    override fun audioTracks(): List<AudioTrack> = tracksOfType("audio").map { index ->
        AudioTrack(
            id = trackField(index, "id").orEmpty(),
            language = trackField(index, "lang") ?: "und",
            label = trackField(index, "title") ?: trackField(index, "lang") ?: "Audio",
            channels = trackField(index, "audio-channels")?.toIntOrNull() ?: DEFAULT_CHANNELS,
            codec = trackField(index, "codec"),
        )
    }

    override fun audioTrack(): AudioTrack? {
        val selected: String = property("aid") ?: return null
        return audioTracks().firstOrNull { track -> track.id == selected }
    }

    override fun audioTrack(track: AudioTrack) {
        mpv.mpv_set_property_string(handle, "aid", track.id)
    }

    override fun subtitleTracks(): List<SubtitleTrack> = tracksOfType("sub").map { index ->
        SubtitleTrack(
            id = trackField(index, "id").orEmpty(),
            language = trackField(index, "lang") ?: "und",
            label = trackField(index, "title") ?: trackField(index, "lang") ?: "Subtitle",
            format = trackField(index, "codec") ?: "vtt",
            forced = trackField(index, "forced") == YES,
        )
    }

    override fun subtitleTrack(): SubtitleTrack? {
        val selected: String = property("sid")?.takeIf { it != "no" } ?: return null
        return subtitleTracks().firstOrNull { track -> track.id == selected }
    }

    override fun subtitleTrack(track: SubtitleTrack?) {
        mpv.mpv_set_property_string(handle, "sid", track?.id ?: "no")
    }

    // ---- renditions --------------------------------------------------------

    /**
     * Each HLS variant, as mpv reports it.
     *
     * This is the whole reason for the engine swap. mpv exposes the master
     * playlist's variants as editions and takes an assignment to `edition`, so
     * a menu built from them can select one. libVLC 3 has no such call: its
     * `quality(level)` returned without doing anything, and the ladder had to be
     * narrowed by rewriting the manifest and reopening the stream.
     *
     * Height and bitrate come out of the edition title, which is what mpv
     * carries from the variant's own attributes. A title it cannot parse yields
     * a rung with zeroes rather than no rung at all — a selectable stream the
     * menu labels poorly beats a stream the viewer cannot reach.
     */
    override fun qualityLevels(): List<QualityLevel> {
        val count: Int = property("edition-list/count")?.toIntOrNull() ?: 0
        return (0 until count).map { index ->
            MpvEditionTitle.parse(property("edition-list/$index/title").orEmpty())
        }
    }

    override fun quality(): QualityLevel? {
        val current: Int = property("edition")?.toIntOrNull() ?: return null
        return qualityLevels().getOrNull(current)
    }

    /**
     * Pin a rendition, or hand adaptation back with null.
     *
     * Null selects mpv's own default edition. mpv does not adapt — no engine on
     * this desktop does, and neither does a browser: hls.js is the adaptive
     * layer there and the element below it sees demuxed fragments. So "auto" is
     * the ladder logic in shared core choosing a rung and calling this again,
     * which is why null is a real default rather than a no-op.
     */
    override fun quality(level: QualityLevel?) {
        val index: Int = level?.let { wanted -> qualityLevels().indexOf(wanted) } ?: -1
        mpv.mpv_set_property_string(handle, "edition", if (index >= 0) index.toString() else "auto")
    }

    /**
     * The space the picture is drawn in.
     *
     * Handed to the ladder rather than to mpv. mpv will play any rendition it is
     * given at any surface size, so a pane smaller than the ladder is not an
     * engine problem — it is a rung nobody should be paying for, which is
     * exactly the decision [AdaptiveLadderDriver] makes. libVLC needed a reopen
     * with a rewritten playlist for the same effect because it cannot be told
     * which variant to take.
     */
    override fun surfaceSize(widthPx: Int, heightPx: Int) {
        ladder.surfaceSize(widthPx, heightPx)
    }

    /**
     * Adaptation, which mpv does not do.
     *
     * mpv selects a variant exactly and then stays on it, so without this a
     * stream opened on a good connection would sit on whichever rung it started
     * with for the whole film. The driver is ticked from the same poll that
     * produces the event spine — one clock, and no timer this class owns that a
     * consumer cannot stop.
     */
    public val ladder: AdaptiveLadderDriver = AdaptiveLadderDriver(this)

    /**
     * Where the picture goes, and the only thing that turns a decoding engine
     * into a visible one.
     *
     * Software rendering rather than a native window. A native window on this
     * desktop paints ABOVE everything the toolkit draws, so an embedded surface
     * would put the picture over the transport bar with no z-order able to move
     * it — the two are not in the same compositor. The same reason libVLC is
     * driven through `vmem` here.
     *
     * Attached once, before anything plays. mpv picks its video output when it
     * first opens a file, and a render context created after that moment
     * renders the NEXT file rather than this one.
     */
    override fun videoFrameSink(sink: VideoFrameSink) {
        require(renderContext == null) { "a frame sink is attached once, before playback" }
        this.sink = sink

        val params = MpvRenderParams().string(MpvRenderParamType.API_TYPE, MPV_RENDER_API_TYPE_SW)
        val holder = PointerByReference()
        val created: Int = mpv.mpv_render_context_create(holder, handle, params.pack())
        require(created >= 0) { "libmpv would not create a software renderer: ${mpv.mpv_error_string(created)}" }

        renderParams = params
        renderContext = holder.value
        renderer.scheduleAtFixedRate(::renderFrame, 0L, FRAME_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Every property that has anything to say about renditions, verbatim.
     *
     * For a failure message and for a person at a terminal. "No renditions" has
     * several causes — the stream never opened, the demuxer exposes variants
     * under a different name, the master was a media playlist all along — and
     * they are indistinguishable from a count of zero.
     */
    public fun describeRenditions(): String = RENDITION_PROPERTIES
        .joinToString(", ") { name -> "$name=${property(name)}" }

    // ---- events ------------------------------------------------------------

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    /**
     * Once the engine is released nothing else may touch the handle.
     *
     * The poller is stopped FIRST and waited for. A scheduled read landing after
     * `mpv_terminate_destroy` is a use-after-free in native code, which on this
     * platform is a process death with a stack naming none of this.
     */
    override fun release() {
        if (released) return
        released = true
        renderer.shutdown()
        renderer.awaitTermination(POLL_MS * SHUTDOWN_POLLS, TimeUnit.MILLISECONDS)
        renderContext?.let(mpv::mpv_render_context_free)
        renderContext = null
        renderParams = null
        events.stop()
        poller.shutdown()
        poller.awaitTermination(POLL_MS * SHUTDOWN_POLLS, TimeUnit.MILLISECONDS)
        mpv.mpv_terminate_destroy(handle)
        bus.clear()
    }

    // ---- the poll ----------------------------------------------------------

    // Transitions only, in the order the media element emits them. Anything
    // thrown here would kill the executor's thread silently and stop every
    // event for the rest of the session, so the whole body is guarded.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun poll() {
        if (released) return
        try {
            announceMetadataOnce()
            val paused: Boolean = announcePlayState()
            announceStall()
            announceTime()

            // Adaptation, on the same clock as the events. Only while something
            // is actually playing: ticking a stopped engine would pick a rung
            // from a measurement of nothing.
            if (!paused) ladder.tick()

            announceEnd()
        } catch (failure: RuntimeException) {
            bus.emit(BackendEvents.STREAM_ERROR, failure.message)
        }
    }

    private fun announceMetadataOnce() {
        val duration: Double = number(DURATION) ?: 0.0
        if (duration <= 0.0 || announcedMetadata) return

        announcedMetadata = true
        lastDuration = duration
        bus.emit(BackendEvents.LOADED_METADATA)
        bus.emit(BackendEvents.CAN_PLAY)
    }

    // Returns whether it is paused, which the adaptation step also needs — read
    // once rather than twice, because two reads of a live property can disagree.
    private fun announcePlayState(): Boolean {
        val paused: Boolean = flag(PAUSE)
        if (lastPaused == paused) return paused

        lastPaused = paused
        bus.emit(if (paused) BackendEvents.PAUSE else BackendEvents.PLAY)
        if (!paused) bus.emit(BackendEvents.PLAYING)
        return paused
    }

    // Stalled, which mpv calls waiting for the cache. `waiting` on the way in
    // only: the media element emits nothing on the way out and the player above
    // reads `playing` for that.
    private fun announceStall() {
        val waiting: Boolean = flag("paused-for-cache")
        if (waiting && !lastWaiting) bus.emit(BackendEvents.WAITING)
        lastWaiting = waiting
    }

    private fun announceTime() {
        val now: Double = number("time-pos") ?: return
        if (now == lastTime) return

        lastTime = now
        bus.emit(BackendEvents.TIME_UPDATE, now)
    }

    private fun announceEnd() {
        val ended: Boolean = flag("eof-reached")
        if (ended && !lastEnded) bus.emit(BackendEvents.ENDED)
        lastEnded = ended
    }

    // ---- the picture -------------------------------------------------------

    private var sink: VideoFrameSink? = null
    private var renderContext: Pointer? = null
    private var renderParams: MpvRenderParams? = null

    // The buffer mpv draws into, reused. A fresh allocation per frame is eight
    // megabytes per 1080p frame handed to the collector twenty-four times a
    // second, which is what collapsed frame delivery the last time a desktop
    // path here allocated per frame.
    private var pixels: Memory? = null
    private var pixelWidth: Int = 0
    private var pixelHeight: Int = 0

    private val renderer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nomercy-mpv-render").apply { isDaemon = true }
    }

    // One frame, at the size mpv says the video is.
    //
    // The SIZE comes from the engine rather than from the view: a render into a
    // buffer of the wrong shape is not an error mpv reports, it is a picture
    // with a diagonal tear that reads as a decoder fault. The view scales
    // afterwards, which it does for libVLC too.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun renderFrame() {
        val context: Pointer = renderContext ?: return
        val target: VideoFrameSink = sink ?: return
        if (released) return

        try {
            drawInto(context, target)
        } catch (failure: RuntimeException) {
            bus.emit(BackendEvents.STREAM_ERROR, failure.message)
        }
    }

    private fun drawInto(context: Pointer, target: VideoFrameSink) {
        val width: Int = number("video-params/w")?.toInt() ?: 0
        val height: Int = number("video-params/h")?.toInt() ?: 0
        if (width <= 0 || height <= 0) return report("no picture yet: video-params is ${width}x$height")

        val buffer: Memory = bufferFor(width, height, target)
        val stride: Long = width.toLong() * BYTES_PER_PIXEL
        val params = MpvRenderParams()
            .ints(MpvRenderParamType.SW_SIZE, width, height)
            .string(MpvRenderParamType.SW_FORMAT, target.pixelOrder.mpvSwFormat)
            .size(MpvRenderParamType.SW_STRIDE, stride)
            .pointer(MpvRenderParamType.SW_POINTER, buffer)

        val drawn: Int = mpv.mpv_render_context_render(context, params.pack())
        if (drawn < 0) {
            return report("render refused: ${mpv.mpv_error_string(drawn)} at ${width}x$height stride $stride")
        }

        target.display(buffer.getByteBuffer(0, buffer.size()))
    }

    // Why the picture is not moving, said once per distinct reason.
    //
    // Sixty times a second is not a log, so each reason is printed the first
    // time it happens and never again. Without this the render loop had exactly
    // two observable states — a picture and a black rectangle — and the black
    // one covers "no video track", "mpv refused the render", "the sink was
    // never attached" and "the video output is null", which is the one it
    // actually was.
    private val reported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun report(reason: String) {
        if (reported.add(reason)) println("[mpv-render] $reason")
    }

    private fun sameSize(width: Int, height: Int): Boolean = width == pixelWidth && height == pixelHeight

    private fun bufferFor(width: Int, height: Int, target: VideoFrameSink): Memory {
        val existing: Memory? = pixels
        if (existing != null && sameSize(width, height)) return existing

        pixelWidth = width
        pixelHeight = height
        target.format(width, height)
        return Memory(width.toLong() * height * BYTES_PER_PIXEL).also { fresh -> pixels = fresh }
    }

    // ---- property helpers --------------------------------------------------

    private fun property(name: String): String? = mpv.property(handle, name)

    private fun number(name: String): Double? = property(name)?.toDoubleOrNull()

    private fun flag(name: String): Boolean = property(name) == YES

    // The indices in `track-list` of one type, so the mapping below reads a
    // field at a time rather than parsing a node structure this binding
    // deliberately does not decode.
    private fun tracksOfType(type: String): List<Int> {
        val count: Int = property("track-list/count")?.toIntOrNull() ?: 0
        return (0 until count).filter { index -> trackField(index, "type") == type }
    }

    private fun trackField(index: Int, field: String): String? = property("track-list/$index/$field")

    private companion object {
        const val POLL_MS: Long = 250L
        const val SHUTDOWN_POLLS: Long = 4L
        const val MILLIS_PER_SECOND: Double = 1000.0
        const val VOLUME_SCALE: Double = 100.0
        const val BITS_PER_BYTE: Double = 8.0
        const val DEFAULT_CHANNELS: Int = 2

        // mpv's booleans are the strings, and its pause property is read and
        // written in six places.
        const val PAUSE: String = "pause"
        const val DURATION: String = "duration"
        const val YES: String = "yes"
        const val NO: String = "no"
        const val BYTES_PER_PIXEL: Int = 4

        // ~60 a second. mpv renders the frame that is due, so asking more often
        // than the display refreshes costs a memcpy and asking less often drops
        // frames the decoder produced.
        const val FRAME_MS: Long = 16L

        val RENDITION_PROPERTIES: List<String> = listOf(
            "edition-list/count",
            "editions",
            "current-edition",
            "track-list/count",
            "hls-bitrate",
            "video-params/w",
            "video-params/h",
            "file-format",
            "path",
        )

        // A variant's own attributes, as mpv puts them in the edition title:
        // "1280x720" and a bandwidth in bits per second. Five digits or more so
        // a resolution cannot be read as a bitrate.
        val WIDTH = Regex("""(\d+)x\d+""")
        val HEIGHT = Regex("""\d+x(\d+)""")
        val BITRATE = Regex("""(\d{5,})""")

        // No window system, no terminal, no OSD, and no config file from the
        // host's home directory — a player whose behaviour depends on the
        // developer's ~/.config/mpv is a player nobody can reproduce a bug on.
        val HEADLESS_OPTIONS: List<Pair<String, String>> = listOf(
            // libmpv, not null. The software render API draws through the
            // "libmpv" video output and nothing else: with vo=null mpv decodes,
            // reports its playhead and renders subtitles into the audio-only
            // path, and mpv_render_context_render returns success having drawn
            // nothing — a black picture with a running clock and live captions,
            // photographed exactly once before this line was written.
            //
            // It still touches no window system. vo=libmpv displays through
            // whatever render context is attached, and until one is it displays
            // nowhere, which is what the headless gate needs.
            "vo" to "libmpv",
            "terminal" to NO,
            "osc" to NO,
            "osd-level" to "0",
            "config" to NO,
            "load-scripts" to NO,
            "idle" to YES,
            "keep-open" to YES,

            // A host that never answers must not be an endless spinner.
            //
            // With no deadline, an unreachable server leaves mpv holding a
            // connection that never opens: video-params stays 0x0, the playhead
            // never moves, and nothing is ever reported — which reads as a
            // player that cannot play the file rather than a server that is
            // down. Ten seconds is long enough for a slow LAN mount and short
            // enough that somebody watching learns something.
            "network-timeout" to "10",
        )
    }
}
