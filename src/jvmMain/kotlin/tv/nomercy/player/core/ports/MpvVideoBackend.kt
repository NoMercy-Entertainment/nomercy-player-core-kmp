// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import tv.nomercy.player.core.natives.libmpv.LibMpv
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
 * State is POLLED rather than pushed. mpv has an event queue reached through
 * `mpv_wait_event`, which returns a struct and needs a thread parked inside the
 * native call; a poll of six properties on a scheduled executor produces the
 * same canonical event spine, is the same shape as the Android and Apple
 * backends' own timer, and cannot wedge on a native call that never returns.
 * The cost is a bounded lateness — one poll interval — on `pause` and `ended`,
 * which are also the two the player above re-derives from its own state.
 */
public class MpvVideoBackend internal constructor(
    private val mpv: LibMpv,
) : VideoBackend {

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

    init {
        poller.scheduleAtFixedRate(::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
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
        mpv.mpv_set_property_string(handle, "pause", if (opts.autoplay) "no" else "yes")
        mpv.mpv_set_property_string(handle, "start", (opts.startPositionMs / MILLIS_PER_SECOND).toString())

        announcedMetadata = false
        lastEnded = false
        mpv.mpv_command(handle, arrayOf("loadfile", url, "replace", null))
    }

    override suspend fun play() {
        mpv.mpv_set_property_string(handle, "pause", "no")
    }

    override fun pause() {
        mpv.mpv_set_property_string(handle, "pause", "yes")
    }

    override fun stop() {
        mpv.mpv_command(handle, arrayOf("stop", null))
    }

    // ---- the playhead ------------------------------------------------------

    override fun currentTime(): Double = number("time-pos") ?: 0.0

    override fun currentTime(seconds: Double) {
        mpv.mpv_command(handle, arrayOf("seek", seconds.toString(), "absolute", null))
    }

    override fun duration(): Double = number("duration") ?: 0.0

    // mpv's own scale is 0..100 and this contract's is 0..1. One conversion,
    // here, rather than at every call site.
    override fun volume(): Float = ((number("volume") ?: 0.0) / VOLUME_SCALE).toFloat()

    override fun volume(value: Float) {
        mpv.mpv_set_property_string(handle, "volume", (value.coerceIn(0f, 1f) * VOLUME_SCALE).toString())
    }

    override fun mute() {
        mpv.mpv_set_property_string(handle, "mute", "yes")
    }

    override fun unmute() {
        mpv.mpv_set_property_string(handle, "mute", "no")
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
        number("duration") == null -> BackendState.LOADING
        flag("pause") -> BackendState.PAUSED
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
            forced = trackField(index, "forced") == "yes",
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
    public fun release() {
        if (released) return
        released = true
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
            val duration: Double = number("duration") ?: 0.0
            if (duration > 0.0 && !announcedMetadata) {
                announcedMetadata = true
                lastDuration = duration
                bus.emit(BackendEvents.LOADED_METADATA)
                bus.emit(BackendEvents.CAN_PLAY)
            }

            val paused: Boolean = flag("pause")
            if (lastPaused != paused) {
                lastPaused = paused
                bus.emit(if (paused) BackendEvents.PAUSE else BackendEvents.PLAY)
                if (!paused) bus.emit(BackendEvents.PLAYING)
            }

            // Stalled, which mpv calls waiting for the cache. `waiting` on the
            // way in only: the media element emits nothing on the way out and
            // the player above reads `playing` for that.
            val waiting: Boolean = flag("paused-for-cache")
            if (waiting && !lastWaiting) bus.emit(BackendEvents.WAITING)
            lastWaiting = waiting

            val now: Double = number("time-pos") ?: return
            if (now != lastTime) {
                lastTime = now
                bus.emit(BackendEvents.TIME_UPDATE, now)
            }

            // Adaptation, on the same clock as the events. Only while something
            // is actually playing: ticking a stopped engine would pick a rung
            // from a measurement of nothing.
            if (!paused) ladder.tick()

            val ended: Boolean = flag("eof-reached")
            if (ended && !lastEnded) bus.emit(BackendEvents.ENDED)
            lastEnded = ended
        } catch (failure: RuntimeException) {
            bus.emit(BackendEvents.STREAM_ERROR, failure.message)
        }
    }

    // ---- property helpers --------------------------------------------------

    private fun property(name: String): String? = mpv.property(handle, name)

    private fun number(name: String): Double? = property(name)?.toDoubleOrNull()

    private fun flag(name: String): Boolean = property(name) == "yes"

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
            "vo" to "null",
            "terminal" to "no",
            "osc" to "no",
            "osd-level" to "0",
            "config" to "no",
            "load-scripts" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
        )
    }
}
