// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.io.File
import java.net.URI
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.media.QualityDescriptor
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

private const val MILLIS_PER_SECOND = 1000.0
private const val FULL_VOLUME_PERCENT = 100

// libVLC turns captions off with track -1.
private const val SUBTITLES_DISABLED = -1

// Long enough for a real discovery on a cold filesystem, short enough that a
// caller waiting on it has not given up.
//
// It was 5 seconds and that was too short, which is worse than it sounds: a
// machine with VLC installed answered "not installed", and every test that gates
// on isAvailable() — the canonical event spine, the crossfade, the track surface,
// the ladder cap — printed its loud skip and passed without running. A gate that
// cannot fail is not a gate, and this is how it happened.
//
// Measured, twice, in a bare JVM against the installed VLC 3.0.23: 7137ms for the
// first MediaPlayerFactory in a process, 684ms for the second. The first pays for
// libVLC indexing its plugin directory because the on-disk plugins.dat is older
// than the DLLs beside it — libVLC says so on stderr, on every launch, and it is
// the same 7 seconds a desktop client pays before its first frame. Regenerating
// that cache with vlc-cache-gen fixes the machine; this number is what makes the
// answer correct on a machine nobody has fixed.
private const val PROBE_TIMEOUT_MS = 20_000L

// What an engine nobody has set a volume on is playing at.
private const val DEFAULT_VOLUME = 1.0f

// The desktop engine, over libVLC.
//
// libVLC decodes practically everything, which is what a desktop client needs
// when the file came off a disc rip and nothing else will touch it. It also
// reports its state in its own vocabulary, on its own thread, so this class is
// two things: a MediaBackend, and a translation of VLC's event stream into the
// canonical one every controller above is written against.
//
// Headless by default: no video surface is attached, so it decodes and reports
// without needing a window. A desktop client attaches its own surface to
// [embeddedPlayer]; the conformance gate does not, which is what lets the gate
// run anywhere libVLC is installed.
public class VlcjVideoBackend private constructor(
    private val factory: MediaPlayerFactory,
    private val ownsFactory: Boolean,
) : VideoBackend {

    // Made its own factory, and will release it.
    public constructor() : this(MediaPlayerFactory(), ownsFactory = true)

    // Given someone else's, and will not. A factory owns libVLC's plugin cache
    // and every player made from it, so an engine that released one it was
    // handed would pull the ground out from under its sibling — which is what
    // two players sharing a factory for a crossfade are.
    public constructor(factory: MediaPlayerFactory) : this(factory, ownsFactory = false)

    private val bus = StringEventBus()

    // Public because video has to be drawn somewhere and only the caller knows
    // where. libVLC renders into a native window handle the UI layer owns, so a
    // backend that kept this private could decode a film and show no one.
    // Nothing above the UI layer touches it: every playback call goes through
    // MediaBackend, and this is the render target alone.
    public val embeddedPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private val player: MediaPlayer = embeddedPlayer

    // VLC reports position in milliseconds and this contract is in seconds. One
    // conversion, here, rather than at every call site.
    private var lastKnownDuration: Double = 0.0

    // The rungs this engine may adapt into. Empty means every rung, which is
    // libVLC's own default and the right one for a caller that has not probed
    // the device.
    public var playableLadder: Collection<QualityDescriptor> = emptyList()

    // How the master playlist gets read. Swapped in tests so the narrowing can be
    // proven against a manifest without a network, and left alone everywhere else.
    internal var masterFetch: HlsMasterFetch = JdkHlsMasterFetch

    // The ladder the current item's manifest declared, which is the only place a
    // desktop can learn it. Empty for a local file or a progressive URL, and then
    // the container's own tracks are the honest answer.
    private var manifestLevels: List<QualityLevel> = emptyList()

    // The space the picture is drawn in, as the surface last measured it. Zero
    // until something reports one, and zero caps nothing.
    private var surfaceWidthPx: Int = 0
    private var surfaceHeightPx: Int = 0

    override fun surfaceSize(widthPx: Int, heightPx: Int) {
        surfaceWidthPx = widthPx
        surfaceHeightPx = heightPx
    }

    // What the caller asked for, and authoritative once it has.
    //
    // libVLC answers -1 for "I do not know yet" — it has no audio output until
    // playback starts — and after that it answers from an output that is not
    // strictly per-player: two engines in one process influence each other's
    // reading. This backend's volume is the gain it was told to apply, which is
    // also the only answer a crossfade can rely on while both engines are live.
    private var requestedVolume: Float? = null

    // Announced once per item, from whichever of the two callbacks arrives
    // first. Twice would make a listener counting canplay think two items
    // loaded.
    private var announcedReadable: Boolean = false

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                announceReadable()
            }

            // Readiness is announced here too, and this is not belt-and-braces.
            // libVLC does not fire mediaPlayerReady for every item — a short one
            // can play to completion without it — so a controller waiting for
            // canplay before it enables anything would wait forever on exactly
            // the items that arrive fastest. An engine that is playing can
            // certainly play, so saying so here is not a guess.
            override fun playing(mediaPlayer: MediaPlayer) {
                announceReadable()
                bus.emit(CanonicalBackendEvent.PLAYING)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.PAUSE)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.ENDED)
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                bus.emit(CanonicalBackendEvent.TIME_UPDATE, newTime / MILLIS_PER_SECOND)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                lastKnownDuration = newLength / MILLIS_PER_SECOND
                // Through the same guard, because knowing the length is knowing
                // the metadata. Emitting it separately announced one item twice,
                // and anything counting loads would have counted two.
                announceReadable()
            }

            // VLC calls this "buffering" and reports a percentage. Zero means it
            // has run dry, which is the moment a chrome should say so; anything
            // else is progress and not worth an event.
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (newCache <= 0f) bus.emit(CanonicalBackendEvent.WAITING)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                bus.emit(CanonicalBackendEvent.ERROR)
            }
        })
    }

    private fun announceReadable() {
        if (announcedReadable) return
        announcedReadable = true
        bus.emit(CanonicalBackendEvent.LOADED_METADATA)
        bus.emit(CanonicalBackendEvent.CAN_PLAY)
    }

    // Whether libVLC can be asked to convert HDR as it decodes.
    //
    // False, and the reason is the render path rather than the format support.
    // libVLC 3.0.23 does tone-map: --tone-mapping offers Hable through to hard
    // clip, measured on the installed build. It belongs to the `gl` and `glwin32`
    // video outputs, which is where libplacebo's shader chain lives — and this
    // backend cannot use either. It renders through `vmem`, a callback handing RV32
    // frames to Skia, because a GL vout on the desktop is a native window that
    // paints ABOVE everything Compose draws: the picture would cover the transport
    // bar and no z-ordering would move it. That trade is settled in
    // ComposeFrameSink and it is not being reopened for this.
    //
    // So the option exists, the converter exists, and neither is reachable from the
    // output this player has to use. Reporting true would claim a conversion that
    // never runs. The rungs ARE identifiable now — the master playlist's
    // VIDEO-RANGE says so, which is what VlcMasterPlaylist reads — so hdrDecision
    // answers CapTo rather than AsIs, and capping to a natively-SDR rendition is
    // the better answer anyway: it costs nothing per frame.
    override val canToneMapHdrToSdr: Boolean = false

    private var hdrFallback: HdrOnSdrFallback = HdrOnSdrFallback.Play

    override fun hdrOnSdrFallback(fallback: HdrOnSdrFallback) {
        hdrFallback = fallback
    }

    private var refusedAsUnplayable: Boolean = false

    // The one rung adaptation may not exceed, from every constraint that has
    // something to say about this item on this machine.
    //
    // Two ceilings, one answer. The dynamic-range ceiling keeps an SDR screen off
    // an HDR rendition; the size ceiling keeps a 3840-wide rendition out of a pane
    // 800 device-pixels tall, which was costing five sixths of the frame rate on
    // software decode. Neither may overwrite the other, so the narrower wins.
    private fun abrCeiling(): QualityLevel? = SizeAbrConstraint.narrower(
        dynamicRangeCeiling(),
        SizeAbrConstraint.abrCeiling(qualityLevels(), surfaceWidthPx, surfaceHeightPx),
    )

    // True when the item must not be opened at all.
    //
    // Decided before prepare rather than after the first frame. Refusing later
    // means the washed-out picture has already been on screen next to the message
    // saying it cannot be shown.
    private fun refusedBeforeOpening(): Boolean {
        val decision: HdrDecision = hdrDecision(
            levels = qualityLevels(),
            displayHdr = desktopDisplayIsHdr(),
            backendCanToneMap = canToneMapHdrToSdr,
            fallback = hdrFallback,
        )
        // CapTo and the rest are answered by narrowing the manifest before libVLC
        // reads it, which is the only place libVLC 3 takes a ladder constraint.
        // Named individually so a new decision cannot fall through unnoticed.
        return when (decision) {
            HdrDecision.Refuse -> { refuseAsUnplayable(); true }
            HdrDecision.AsIs,
            HdrDecision.ToneMap,
            HdrDecision.PlayUnconverted,
            is HdrDecision.CapTo,
            -> false
        }
    }

    // Stopped as well as reported: an error nothing acts on would leave the
    // washed-out picture on screen next to a message saying it cannot be shown.
    private fun refuseAsUnplayable() {
        if (refusedAsUnplayable) return
        refusedAsUnplayable = true
        player.controls().stop()
        bus.emit(CanonicalBackendEvent.ERROR, CoreErrorCodes.HDR_UNPLAYABLE)
    }

    override suspend fun load(url: String, opts: LoadOptions) {
        announcedReadable = false
        refusedAsUnplayable = false
        bus.emit(CanonicalBackendEvent.LOAD_START, url)

        // Off the caller's thread: this is one small HTTP read, and load() is
        // called from a UI coroutine.
        val master: VlcMasterPlaylist? = withContext(Dispatchers.IO) {
            VlcMasterPlaylist.of(url, opts.headers, masterFetch)
        }
        manifestLevels = master?.let { VlcLadderNarrowing.levelsOf(it.ladder) }.orEmpty()

        if (refusedBeforeOpening()) return

        // prepare rather than play: loading and starting are separate decisions
        // above, and an engine that started on its own would ignore a refused
        // beforePlay.
        // The ladder constraint travels with the media rather than being applied
        // afterwards: libVLC decides which rung to open while it reads the
        // manifest, so a limit set after prepare is a limit set too late.
        // The array is built once per load and vlcj's signature is a vararg, so
        // the copy detekt warns about is the call convention rather than a cost
        // worth avoiding — a load happens per item, not per frame.
        @Suppress("SpreadOperator")
        player.media().prepare(
            narrowedLocation(master) ?: playableLocation(url),
            *VlcAdaptiveOptions.optionsFor(playableLadder).toTypedArray(),
        )
    }

    // A local playlist offering only the rungs this machine may use, or null to
    // open the original URL untouched.
    //
    // Null covers every case that is not an adaptive stream and every case where
    // reading or narrowing failed, so a manifest this cannot handle plays exactly
    // as it did before any of this existed.
    private fun narrowedLocation(master: VlcMasterPlaylist?): String? {
        if (master == null) return null
        return master.narrowedTo(
            VlcLadderNarrowing.keep(
                declared = master.ladder,
                playable = playableLadder,
                maxHeight = abrCeiling()?.height,
                sdrOnly = dynamicRangeCeiling() != null,
            ),
        )
    }

    private fun dynamicRangeCeiling(): QualityLevel? =
        HdrAbrConstraint.abrCeiling(qualityLevels(), desktopDisplayIsHdr())

    // libVLC will not open file:/C:/x, which is exactly what File.toURI()
    // produces and therefore what a desktop caller passes without thinking about
    // it. The failure is an error event and silence, which reads as a broken
    // engine rather than a URL it did not like. Any file: URI becomes a plain
    // path here; everything else is handed over untouched, because a network URL
    // is libVLC's business and not this class's.
    private fun playableLocation(url: String): String {
        if (!url.startsWith("file:")) return url
        return runCatching { File(URI(url)).absolutePath }.getOrDefault(url)
    }

    override suspend fun play() {
        bus.emit(CanonicalBackendEvent.PLAY)
        player.controls().play()
    }

    override fun pause() {
        player.controls().setPause(true)
    }

    override fun stop() {
        player.controls().stop()
    }

    // libVLC answers -1 for "I do not know yet", on time as well as on
    // length. A negative position is meaningless to everything above and a
    // scrubber renders it as a bar pointing the wrong way.
    override fun currentTime(): Double {
        val reported: Long = player.status().time()
        return if (reported > 0) reported / MILLIS_PER_SECOND else 0.0
    }

    override fun currentTime(seconds: Double) {
        player.controls().setTime((seconds * MILLIS_PER_SECOND).toLong())
    }

    // VLC's own length is -1 until it has read the container, so the last value
    // it reported is the honest answer rather than a negative number a scrubber
    // would try to divide by.
    override fun duration(): Double {
        val reported: Long = player.status().length()
        return if (reported > 0) reported / MILLIS_PER_SECOND else lastKnownDuration
    }

    // libVLC answers -1 for "I do not know yet" on volume, the same way it does
    // on time and length — and it does not know until an audio output exists,
    // which is after the first play rather than after the first load. A negative
    // gain reaching a mixer is a slider at the wrong end and a fade that reads
    // as never having happened.
    //
    // The last value set is remembered so a caller reads back what it asked for
    // in that window, and the engine's own answer takes over once it has one.
    override fun volume(): Float {
        requestedVolume?.let { return it }
        val reported: Int = player.audio().volume()
        return if (reported < 0) DEFAULT_VOLUME else reported / FULL_VOLUME_PERCENT.toFloat()
    }

    override fun volume(value: Float) {
        val clamped: Float = value.coerceIn(0f, 1f)
        requestedVolume = clamped
        player.audio().setVolume((clamped * FULL_VOLUME_PERCENT).toInt())
    }

    override fun mute() {
        player.audio().isMute = true
    }

    override fun unmute() {
        player.audio().isMute = false
    }

    // libVLC exposes cache fullness as a percentage rather than a buffered
    // range, so the honest answer is how far ahead of the playhead it claims to
    // be, and zero when it will not say.
    override fun buffered(): Double = currentTime()

    override fun playbackRate(): Double = player.status().rate().toDouble()

    override fun playbackRate(rate: Double) {
        player.controls().setRate(rate.toFloat())
    }

    override fun state(): BackendState = when {
        player.status().isPlaying -> BackendState.PLAYING
        player.status().isPlayable -> BackendState.READY
        else -> BackendState.IDLE
    }

    // The manifest's ladder where there is one, the container's tracks where
    // there is not.
    //
    // The manifest comes first because libVLC's own answer is not a ladder at all:
    // its adaptive demuxer exposes the ONE variant it is currently decoding as a
    // video track, so a four-rung stream read as a single rung — and read as SDR,
    // because the track struct carries no transfer function. A quality menu built
    // from that showed one entry and the dynamic-range decision had nothing to
    // decide about.
    override fun qualityLevels(): List<QualityLevel> =
        manifestLevels.ifEmpty { containerLevels() }

    private fun containerLevels(): List<QualityLevel> =
        player.media().info()?.videoTracks().orEmpty().map { track ->
            QualityLevel(
                height = track.height(),
                bitrate = VlcTrackMapper.bitrateOf(track.bitRate()),
                codec = VlcTrackMapper.codecFamily(track.codecName()),
                dynamicRange = VlcTrackMapper.dynamicRange(),
                width = track.width().takeIf { it > 0 },
                label = VlcTrackMapper.labelOf(track.description(), track.language()),
            )
        }

    override fun quality(): QualityLevel? {
        if (manifestLevels.isNotEmpty()) return decodingRung()

        val selected: Int = player.video().track()
        if (selected < 0) return null

        val tracks = player.media().info()?.videoTracks().orEmpty()
        val index: Int = tracks.indexOfFirst { it.id() == selected }
        return containerLevels().getOrNull(index)
    }

    // Which declared rung the engine is decoding, matched on height.
    //
    // NEAREST height rather than equal. A manifest declares the resolution its
    // author wrote down and libVLC reports the one the encoder actually produced,
    // and the two differ: Sintel's 4K variant is declared 3840x1635 and decodes at
    // 3840x1666. Requiring equality means a menu with nothing selected, on the
    // rung that is playing.
    private fun decodingRung(): QualityLevel? {
        val decoding: Int = player.media().info()?.videoTracks().orEmpty()
            .firstOrNull()?.height() ?: return null
        if (decoding <= 0) return null
        return manifestLevels.minByOrNull { abs(it.height - decoding) }
    }

    // Null is libVLC's own choice, which for a container means its default
    // track. A descriptor is matched against the engine's own list, and a rung
    // it no longer has leaves the selection alone rather than picking a
    // neighbour.
    //
    // Adaptive streams are not selectable here and this returns without pretending
    // otherwise. libVLC 3's adaptive demuxer chooses its own representation and
    // publishes one video track; there is no runtime call that pins a different
    // one, so the only way to honour a pick is to narrow the manifest and reopen —
    // which is a reload with a position restore, and it is not this method's job to
    // do silently. The constraint that a display and a pane impose is applied at
    // load, where libVLC does take it.
    override fun quality(level: QualityLevel?) {
        if (manifestLevels.isNotEmpty()) return

        val tracks = player.media().info()?.videoTracks().orEmpty()
        if (level == null) {
            tracks.firstOrNull()?.let { player.video().setTrack(it.id()) }
            return
        }

        val index: Int = QualityMatcher.match(level, containerLevels()) ?: return
        tracks.getOrNull(index)?.let { player.video().setTrack(it.id()) }
    }

    override fun audioTracks(): List<AudioTrack> =
        player.media().info()?.audioTracks().orEmpty().map { track ->
            AudioTrack(
                id = track.id().toString(),
                language = VlcTrackMapper.languageOf(track.language()),
                label = VlcTrackMapper.labelOf(track.description(), track.language()),
                channels = track.channels().coerceAtLeast(1),
                codec = VlcTrackMapper.codecFamily(track.codecName()),
            )
        }

    override fun audioTrack(): AudioTrack? =
        audioTracks().firstOrNull { it.id == player.audio().track().toString() }

    override fun audioTrack(track: AudioTrack) {
        track.id.toIntOrNull()?.let { player.audio().setTrack(it) }
    }

    override fun subtitleTracks(): List<SubtitleTrack> =
        player.media().info()?.textTracks().orEmpty().map { track ->
            SubtitleTrack(
                id = track.id().toString(),
                language = VlcTrackMapper.languageOf(track.language()),
                label = VlcTrackMapper.labelOf(track.description(), track.language()),
                format = VlcTrackMapper.codecFamily(track.codecName()),
            )
        }

    override fun subtitleTrack(): SubtitleTrack? =
        subtitleTracks().firstOrNull { it.id == player.subpictures().track().toString() }

    // libVLC turns captions off with track -1, which is a selection rather than
    // an error — the same thing a null descriptor means everywhere else.
    override fun subtitleTrack(track: SubtitleTrack?) {
        val id: Int = track?.id?.toIntOrNull() ?: SUBTITLES_DISABLED
        player.subpictures().setTrack(id)
    }

    override fun on(event: String, fn: (Any?) -> Unit): Unit = bus.on(event, fn)

    override fun off(event: String, fn: (Any?) -> Unit): Unit = bus.off(event, fn)

    // Releasing is not optional: libVLC holds native resources and a player that
    // is dropped without this leaks a decoder thread per item.
    public fun release() {
        player.release()
        if (ownsFactory) factory.release()
    }

    public companion object {
        // True when libVLC is present and binds. A desktop build that assumed it
        // would fail at the first play with a linker error instead of a message
        // anyone can act on.
        public fun isAvailable(): Boolean = whyUnavailable() == null

        // The reason, so a desktop client can say "install VLC" rather than
        // "playback failed". Null when it binds.
        // LinkageError, not its subclasses one at a time. A machine without
        // libVLC produced ExceptionInInitializerError from VLCJ's static
        // initialiser, which is neither UnsatisfiedLinkError nor
        // NoClassDefFoundError — the two this originally caught. Naming the
        // shapes of absence individually means missing one, and the one missed
        // is the one that reaches a user.
        //
        // Bounded, and on a thread of its own. Answering this question means
        // building a real factory, which is how VLCJ discovers the native
        // library — and on a machine without a display that can sit rather than
        // fail. A caller asking "do you have VLC" is often a UI deciding what to
        // offer; blocking it forever is worse than saying no.
        //
        // Memoized because the answer cannot change while the process runs, and
        // because paying seconds for it twice would be paying it on a click.
        @Suppress("TooGenericExceptionCaught")
        public fun whyUnavailable(): String? = probe

        private val probe: String? by lazy { probeWithin(PROBE_TIMEOUT_MS) }

        @Suppress("TooGenericExceptionCaught")
        private fun probeWithin(millis: Long): String? {
            val answer = java.util.concurrent.atomic.AtomicReference<String?>(
                "libVLC did not answer within ${millis}ms: it is missing, or its discovery is blocked",
            )
            val prober = Thread {
                answer.set(
                    try {
                        MediaPlayerFactory().release()
                        null
                    } catch (missing: LinkageError) {
                        "libVLC is not installed, or is the wrong architecture: ${missing.message}"
                    } catch (refused: RuntimeException) {
                        // VLCJ's discovery throws this when it finds nothing.
                        "libVLC could not be located: ${refused.message}"
                    },
                )
            }
            // A daemon, so a probe that never returns cannot hold the process
            // open at exit — which is exactly how this presented: a test task
            // that started and never finished.
            prober.isDaemon = true
            prober.name = "nomercy-libvlc-probe"
            prober.start()
            prober.join(millis)
            return answer.get()
        }
    }
}
