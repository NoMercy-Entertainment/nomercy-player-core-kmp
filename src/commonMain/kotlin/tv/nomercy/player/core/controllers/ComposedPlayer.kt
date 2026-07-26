// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.KIT_VERSION
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.NotImplementedError
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.AuthFailedPayload
import tv.nomercy.player.core.events.AuthRefreshedPayload
import tv.nomercy.player.core.events.CastStatePayload
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.events.TransitionCancelledPayload
import tv.nomercy.player.core.events.PlaylistReadyPayload
import tv.nomercy.player.core.events.PlaylistResolvingPayload
import tv.nomercy.player.core.events.TimeState
import tv.nomercy.player.core.events.PlaybackMetrics
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.Chapter
import tv.nomercy.player.core.media.ChapterTrack
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.media.TitleTokens
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.player.CastState
import tv.nomercy.player.core.player.NetworkState
import tv.nomercy.player.core.player.VisibilityState
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.SetupState
import tv.nomercy.player.core.player.VolumeState
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.PlayerState
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState
import tv.nomercy.player.core.plugin.ChromeContribution
import tv.nomercy.player.core.plugin.ChromeSlot
import tv.nomercy.player.core.plugin.ContributionBinding
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginHost
import tv.nomercy.player.core.plugin.PluginRegistry
import tv.nomercy.player.core.ports.AnnouncementLevel
import tv.nomercy.player.core.ports.Announcer
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.DecodeCapability
import tv.nomercy.player.core.ports.DecodeProfile
import tv.nomercy.player.core.ports.Platform
import tv.nomercy.player.core.ports.UnconfiguredPlatform
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.CueParserRegistry
import tv.nomercy.player.core.ports.DefaultPreloadStrategy
import tv.nomercy.player.core.ports.Device
import tv.nomercy.player.core.ports.GaplessTransitionStrategy
import tv.nomercy.player.core.ports.PreloadStrategy
import tv.nomercy.player.core.ports.TransitionStrategy
import tv.nomercy.player.core.ports.defaultClock
import tv.nomercy.player.core.ports.currentDevice
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.Fetcher
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.SubtitleTrack
import tv.nomercy.player.core.ports.QualityMode
import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.MediaBackend
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeFactoryOptions
import tv.nomercy.player.core.ports.ResolvedUrl
import tv.nomercy.player.core.ports.Storage
import tv.nomercy.player.core.ports.StreamFactory
import tv.nomercy.player.core.ports.StreamRegistry
import tv.nomercy.player.core.ports.UrlCategory
import tv.nomercy.player.core.ports.UrlResolution
import tv.nomercy.player.core.ports.UrlResolver
import tv.nomercy.player.core.ports.UrlResolverContext
import tv.nomercy.player.core.ports.Translations
import tv.nomercy.player.core.ports.Translator

// The controllers wired together over one context, and the proof that they are
// a player rather than eight pieces that happen to compile.
//
// Composition, not inheritance: this owns its controllers and forwards. A
// per-library player does the same over the same context rather than
// subclassing a base and hoping the overrides line up.
//
// Open, and every member with it. A consumer replacing one behaviour overrides
// one method; a consumer replacing the composition builds their own from the
// same controllers.
// Ten seconds, which is what every player's skip button does and what a viewer
// expects without being told.
private const val SKIP_SECONDS = 10.0

// Every 2xx. A playlist behind a redirect the fetcher already followed arrives
// as a 200; anything else did not arrive.
private val OK_STATUS = 200..299

// The reason a transition ends when a consumer swaps the strategy under it.
private const val STRATEGY_REPLACED = "strategy-replaced"

// Under this and a 1080p rung is not going to hold. The number is the web
// player's, kept so a consumer moving between them sees the same badge at the
// same moment rather than discovering that native calls it slow later.
private const val SLOW_DOWNLINK_MBPS = 1.5

// Null means the platform cannot measure it, which is not the same as slow: a
// desktop with no such API would otherwise be permanently degraded.
private fun isSlow(downlinkMbps: Double?): Boolean =
    downlinkMbps != null && downlinkMbps > 0.0 && downlinkMbps < SLOW_DOWNLINK_MBPS

// Enough randomness to tell two players in one log apart, and no more.
//
// Not a UUID: this identifies an instance inside a process, and the id that has
// to be unique across devices is the device's, which Connect already carries.
// A sixteen-bit suffix reads at a glance, which a UUID does not.
private const val ID_ALPHABET_SIZE = 0x10000

private fun generatePlayerId(): String = "nmplayer-" + Random.nextInt(ID_ALPHABET_SIZE).toString(HEX)

private const val HEX = 16

// Five points per nudge, which is fine enough to tune by and coarse enough that
// a held-down remote key reaches the end in about the time a viewer expects.
private const val VOLUME_STEP = 5

// What language() answers when nobody injected a translator. Not "en": no
// language was chosen, and claiming one would have a UI decide text direction
// and number formatting from a default that means "unconfigured".
private const val UNTRANSLATED = ""

@Suppress("TooManyFunctions")
public open class ComposedPlayer(
    backend: MediaBackend? = null,
    private val logger: Logger = SilentLogger,
    private val storage: Storage = InMemoryStorage(),
    private val translator: Translator? = null,
    // Supplied by the chrome, which is the only layer that can reach a platform
    // accessibility API. Absent means the player says nothing.
    private val announcer: Announcer? = null,
    // Wake lock, connectivity, visibility and codec probing, from the app's own
    // observers. A library that registered its own would be a second set beside
    // them, disagreeing about state and outliving the screen.
    //
    // Not defaultPlatform(): on Android that reaches for a Context the host has
    // to have installed, and a player must be constructible before any of that
    // has happened. A host that wants the real monitors passes defaultPlatform()
    // once its own setup is done.
    private val platform: Platform = UnconfiguredPlatform,
    // Injectable so a test decides what time it is, and so a fleet in a Connect
    // session can share one: two devices comparing timestamps have to agree
    // about now.
    clock: Clock = defaultClock(),
    // Null by default, and the failure when a plugin asks anyway is a named
    // one. A player that opened its own connections would be a second HTTP
    // stack beside the app's, with its own idea of auth and retries.
    private val fetcher: Fetcher? = null,
    // The same backend again when it can report a ladder and tracks.
    //
    // Passed rather than tested for: asking a MediaBackend whether it is really
    // a VideoBackend is a cast, and a caller saying what it has beats the
    // library guessing. A player built on an audio-only engine still works; it
    // just has no quality menu.
    private val video: VideoBackend? = null,
    // Nullable rather than defaulted, so a caller that cannot see Kotlin default
    // arguments can still leave it out. From Swift the default is invisible and
    // the type was non-optional, which meant building a player required
    // constructing a Kotlin CoroutineScope first — a front door no iOS engineer
    // should have to find.
    scope: CoroutineScope? = null,
    // Names this instance in logs, in a Connect message and in a registry of
    // more than one player. Generated when the host does not care, because a
    // required id would be a construction argument every caller has to invent;
    // named when it does, because "video" and "music" read better in a log than
    // two hex strings.
    public val playerId: String = generatePlayerId(),
) : PluginHost {

    public val context: PlayerContext = PlayerContext(backend = backend)

    public val queue: QueueController = QueueController(context)
    public val transport: TransportController = TransportController(context, queue)
    public val volume: VolumeController = VolumeController(context)
    public val time: TimeController = TimeController(context, queue, transport)
    public val state: StateController = StateController(context, queue, time)
    private val playerScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob())

    public val plugins: PluginRegistry = PluginRegistry(this, KIT_VERSION, playerScope)
    public val lifecycle: LifecycleController = LifecycleController(context, plugins, ::report)

    // What the engine reports, turned into what the player says. Without it
    // the controllers drive the engine and nothing listens to it come back.
    public val bridge: BackendBridge = BackendBridge(context)

    public val bandwidth: BandwidthController = BandwidthController()

    public val metrics: MetricsController = MetricsController(clock)

    // Built at setup, when the configured inactivity window is known. Until
    // then there is nothing to count down from.
    public var activity: ActivityController = ActivityController(context, playerScope, PlayerConfig().inactivityMs)
        private set

    init {
        queue.transport = transport
        queue.wireQueue()
        backend?.let { bridge.attach(it) }

        // Counting restarts with every item, because the question a metric
        // answers is about this thing playing now. A session spanning a whole
        // queue would answer "how long has this been playing" with how long the
        // app has been open.
        context.on(CoreEvents.Item) { metrics.startSession() }

        // Resuming re-arms the countdown even when the resume came from
        // somewhere no UI saw it — a headphone button, a car head unit.
        // Otherwise controls shown during a pause stay up for the rest of the
        // film: the countdown expired harmlessly while paused, and nothing
        // would arm it again.
        context.on(CoreEvents.Play) { activity.onPlaybackResumed() }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public open suspend fun setup(config: PlayerConfig = PlayerConfig()) {
        // Taken here rather than read from the config on every call, so a later
        // baseUrl(url) is not silently overruled by the value setup was given.
        configuredBaseUrl = config.baseUrl
        configuredImageBaseUrl = config.baseImageUrl
        configuration = config
        activity = ActivityController(context, playerScope, config.inactivityMs)
        lifecycle.setup(config)

        // Controls start visible: the viewer just started a player, which is
        // activity. This arms the countdown that fades them once playback runs.
        activity.bumpActivity()
    }

    public open fun ready(): Deferred<Unit> = lifecycle.ready()

    public open suspend fun dispose(opts: ActionOptions = ActionOptions()) {
        lifecycle.dispose(opts)
    }

    public open fun phase(): PlayerPhase = context.phase

    // The configuration this player was set up with.
    //
    // The defaults until setup runs, rather than null. A chrome asking how long
    // the autohide window is before setup gets the answer it will have, and a
    // caller reading a field does not have to handle "not configured yet" on
    // every one of them.
    public open fun options(): PlayerConfig = configuration

    // Which events are being dispatched around this call, outermost first.
    //
    // A before-listener uses it to tell "refuse a seek" from "refuse a seek
    // that came from the queue advancing", which are different rules and
    // indistinguishable from the payload alone.
    public open fun dispatching(): List<String> = context.dispatching()

    // ── Auth ─────────────────────────────────────────────────────────────────

    // How an item's url becomes one the backend can fetch.
    //
    // No token crosses this surface in either direction. A NoMercy server signs
    // playback urls, and the signing belongs behind the controller — a getter
    // for the bearer would put the secret in reach of every plugin and every
    // consumer who can see the player, which is the whole population.
    public open fun auth(): AuthController? = context.auth

    public open fun auth(controller: AuthController?) {
        context.auth = controller
    }

    // Ask for a new token, because the one in use stopped working.
    //
    // Announced either way. A cached fetcher and a telemetry sink both need to
    // know a refresh happened; a player with no auth configured has nothing to
    // refresh and reports success, because there is no failure in having no
    // token to renew.
    public open suspend fun refreshAuth(): Boolean {
        val refreshed: Boolean = context.auth?.refresh() ?: true
        if (refreshed) {
            context.emit(CoreEvents.AuthRefreshed, AuthRefreshedPayload(now().toDouble()))
        } else {
            context.emit(CoreEvents.AuthFailed, AuthFailedPayload())
        }
        return refreshed
    }

    // ── Engine ───────────────────────────────────────────────────────────────

    // The engine, for the things no port covers.
    //
    // Public because pretending otherwise does not work: a consumer needing an
    // ExoPlayer reference for a Media3 UI component, or an AVPlayer for a
    // picture-in-picture controller, will reach for it, and a library that hid
    // it would be forcing a cast through a property that happens to be public
    // somewhere else. Guidance, not walls.
    public open fun backend(): MediaBackend? = context.backend

    // Load an item without going through the queue.
    //
    // What a caller wants when the queue is not the model: a preview, a trailer,
    // a single file opened from a file manager.
    public open suspend fun load(item: PlaylistItem, opts: LoadOptions = LoadOptions()): Unit =
        context.load(item, opts)

    // Whether this device can decode that.
    //
    // Three answers, not one: supported says it plays, smooth says it plays at
    // full rate, powerEfficient says it does so in hardware. A rendition can be
    // supported and still be the wrong choice on a battery, and a ladder
    // filtered on supported alone puts a phone on a rung that drains it.
    public open suspend fun canPlay(profile: DecodeProfile): DecodeCapability =
        platform.capabilities.canDecode(profile)

    // ── Environment ──────────────────────────────────────────────────────────

    // Whether anything can be fetched, and roughly how well.
    //
    // Three states from two questions the monitor answers. Offline wins over
    // slow, because a downlink figure from a connection that is gone is stale
    // by definition.
    public open fun networkState(): NetworkState = when {
        !platform.network.isOnline() -> NetworkState.OFFLINE
        isSlow(platform.network.downlinkMbps()) -> NetworkState.SLOW
        else -> NetworkState.ONLINE
    }

    // Not whether the app is foregrounded: a Compose screen that scrolled away,
    // a tab behind another and a backgrounded app are all hidden, and the
    // pause-when-hidden policy wants all three.
    public open fun visibilityState(): VisibilityState =
        if (platform.visibility.isVisible()) VisibilityState.VISIBLE else VisibilityState.HIDDEN

    // The host platform itself, for the two things a chrome needs that no state
    // enum covers: whether fullscreen and picture-in-picture exist here. Both
    // are nullable on the port, so a chrome asking gets an honest absent rather
    // than a control that silently does nothing.
    public open fun platform(): Platform = platform

    // What the engine is doing with the stream, as the engine sees it.
    //
    // Distinct from playState and bufferState: those are the player's account
    // of what a viewer sees, this is the engine's own. A support log wants both
    // — a player that says PLAYING while its engine says IDLE is a bug the two
    // together name and neither does alone.
    public open fun streamState(): BackendState = context.backend?.state() ?: BackendState.IDLE

    // Whether there is anywhere to cast to, and whether playback is there now.
    //
    // Written by whatever owns the discovery — a cast plugin, a Connect
    // session — because core has no business scanning a network. UNAVAILABLE
    // until something says otherwise, which is honest: no discovery has run.
    public open fun castState(): CastState = castTarget

    public open fun castState(state: CastState) {
        if (castTarget == state) return
        castTarget = state
        context.emit(CoreEvents.CastState, CastStatePayload(state.token))
    }

    private var castTarget: CastState = CastState.UNAVAILABLE

    // ── Strategies ───────────────────────────────────────────────────────────

    // What to fetch ahead of the next item, and what the change to it looks and
    // sounds like.
    //
    // Two strategies rather than one because they answer different questions: a
    // consumer that wants a gapless join has no opinion about which assets get
    // warmed, and one that prefetches artwork has none about crossfades.
    public open fun preloadStrategy(): PreloadStrategy = preload

    public open fun setPreloadStrategy(strategy: PreloadStrategy) {
        // The outgoing strategy may have work in flight against an item this
        // player is about to stop caring about.
        preload.cancel()
        preload = strategy
    }

    public open fun transitionStrategy(): TransitionStrategy = transition

    // Cancels a transition already under way, and says so.
    //
    // Swapping mid-fade would otherwise leave the outgoing item at whatever gain
    // the old strategy last set — audible as a track that never comes back up.
    // The cancel event is what tells a chrome to stop drawing a crossfade that
    // is no longer happening.
    public open fun setTransitionStrategy(strategy: TransitionStrategy) {
        transition.cancel(STRATEGY_REPLACED)
        context.emit(CoreEvents.TransitionCancelled, TransitionCancelledPayload(STRATEGY_REPLACED))
        transition = strategy
    }

    private var preload: PreloadStrategy = DefaultPreloadStrategy()
    // Cutting at the natural end is the right core default: two streams
    // overlapping is a dissolve nobody asked for, and the music library swaps in
    // a crossfade where one is wanted.
    private var transition: TransitionStrategy = GaplessTransitionStrategy()

    // ── Activity ─────────────────────────────────────────────────────────────

    // Say something to a screen reader.
    //
    // Blank text is dropped: a screen reader interrupting itself to say nothing
    // is worse than silence, and a formatted string that came out empty is the
    // usual way that happens.
    public open fun announce(text: String, level: AnnouncementLevel = AnnouncementLevel.POLITE) {
        if (text.isBlank()) return
        announcer?.announce(text, level)
    }

    public open fun bumpActivity(): Unit = activity.bumpActivity()

    public open fun activityTracking(): Boolean = activity.activityTracking()

    public open fun activityTracking(enabled: Boolean): Unit = activity.activityTracking(enabled)

    // ── Instrumentation ──────────────────────────────────────────────────────

    public open fun metrics(): PlaybackMetrics = metrics.metrics()

    public open fun recordMetric(metric: Metric, value: Double): Unit = metrics.recordMetric(metric, value)

    public open fun recordMetric(name: String, value: Double): Unit = metrics.recordMetric(name, value)

    public open fun now(): Long = metrics.now()

    // ── Urls ─────────────────────────────────────────────────────────────────

    // The prefix relative paths are joined to.
    //
    // Writable at runtime rather than setup-only: a self-hosted server that
    // moves — a tunnel coming up, a LAN address replacing a public one — should
    // not need the player torn down and rebuilt to follow it.
    public open fun baseUrl(): String? = configuredBaseUrl

    public open fun baseUrl(url: String?) {
        configuredBaseUrl = url
    }

    public open fun urlResolver(): UrlResolver? = resolver

    public open fun urlResolver(fn: UrlResolver?) {
        resolver = fn
    }

    // A url, absolute and parsed.
    //
    // Artwork joins to the image base and everything else to the media base,
    // because they are usually different hosts: posters come from a CDN and
    // media from the server the viewer is signed in to.
    public open suspend fun resolveUrl(url: String, category: UrlCategory = UrlCategory.MEDIA): ResolvedUrl {
        val base: String? = baseFor(category)
        val fallback: suspend (String) -> ResolvedUrl = { UrlResolution.resolve(it, base) }

        val resolved: ResolvedUrl = resolver
            ?.resolve(url, UrlResolverContext(base, category, fallback))
            ?: fallback(url)

        // A relative poster reaches the OS media session or a Cast receiver as a
        // path with nothing to resolve it against, and fails in a process that
        // cannot report back. Saying so here is the only place it is visible.
        if (resolved.relative && category.needsAbsolute) {
            logger.warn(
                "resolveUrl: a ${category.token} url stayed relative and will not load off-process. " +
                    "Set baseImageUrl, or supply a urlResolver. Raw: \"$url\"",
            )
        }
        return resolved
    }

    private var configuration: PlayerConfig = PlayerConfig()
    private var configuredBaseUrl: String? = null
    private var configuredImageBaseUrl: String? = null
    private var resolver: UrlResolver? = null

    private fun baseFor(category: UrlCategory): String? =
        if (category.needsAbsolute) configuredImageBaseUrl ?: configuredBaseUrl else configuredBaseUrl

    // ── Device ───────────────────────────────────────────────────────────────

    // What the player is running on, asked of the platform rather than guessed.
    //
    // On the player because that is where a chrome, a plugin and a quality
    // heuristic all reach for it, and because a consumer should not have to
    // know which of the three ways to ask its platform is the one that works
    // without a Context.
    public open fun device(): Device = currentDevice()

    public open fun isTv(): Boolean = device().isTv

    public open fun isMobile(): Boolean = device().isMobile

    public open fun isDesktop(): Boolean = device().isDesktop

    // ── Transport ────────────────────────────────────────────────────────────

    public open suspend fun play(opts: ActionOptions = ActionOptions()): Unit = transport.play(opts)

    public open suspend fun pause(opts: ActionOptions = ActionOptions()): Unit = transport.pause(opts)

    public open suspend fun stop(opts: ActionOptions = ActionOptions()): Unit = transport.stop(opts)

    public open suspend fun togglePlayback(opts: ActionOptions = ActionOptions()): Unit =
        transport.togglePlayback(opts)

    public open suspend fun next(opts: ActionOptions = ActionOptions()): Unit = transport.next(opts)

    public open suspend fun previous(opts: ActionOptions = ActionOptions()): Unit = transport.previous(opts)

    public open suspend fun restart(opts: ActionOptions = ActionOptions()): Unit = transport.restart(opts)

    // ── Time ─────────────────────────────────────────────────────────────────

    public open fun time(): Double = time.time()

    public open suspend fun time(seconds: Double, opts: ActionOptions = ActionOptions()): Unit =
        time.time(seconds, opts)

    public open fun duration(): Double = time.duration()

    public open fun timeData(): TimeState = time.timeData()

    // Why the picture is not moving, when it is not moving.
    //
    // Separate from playState because "paused" and "waiting for data" are the
    // same stillness on screen and completely different things to say about it.
    public open fun bufferState(): BufferState = context.bufferState

    // The chapters of the item being played, and where the playhead sits in
    // them.
    //
    // Held on the player rather than parsed from the item, because chapters
    // arrive from wherever the host got them — a scan, a sidecar, an API — and a
    // player that insisted on parsing would be a second parser disagreeing with
    // the first.
    public open fun chapters(): List<Chapter> = chapterTrack.chapters

    public open fun chapters(list: List<Chapter>) {
        chapterTrack = ChapterTrack(list)
    }

    public open fun chapter(): Chapter? = chapterTrack.at(time())

    // Null at the last chapter is the caller's cue to skip to the end of the
    // item; jumping to zero there would restart the film.
    public open suspend fun nextChapter(opts: ActionOptions = ActionOptions()) {
        chapterTrack.nextStart(time())?.let { time.time(it, opts) }
    }

    // Within a few seconds of a chapter's start this goes to the one before, and
    // after that it restarts the current one — what every music player does,
    // because pressing previous mid-chapter almost always means "start this
    // again".
    public open suspend fun previousChapter(opts: ActionOptions = ActionOptions()) {
        chapterTrack.previousStart(time())?.let { time.time(it, opts) }
    }

    public open suspend fun seekToChapter(index: Int, opts: ActionOptions = ActionOptions()) {
        chapterTrack.chapters.getOrNull(index)?.let { time.time(it.startTime, opts) }
    }

    private var chapterTrack: ChapterTrack = ChapterTrack(emptyList())

    // Skip, clamped at both ends. A rewind landing at a negative position makes
    // one engine seek to the start and another refuse.
    public open suspend fun forward(seconds: Double = SKIP_SECONDS, opts: ActionOptions = ActionOptions()): Unit =
        time.forward(seconds, opts)

    public open suspend fun rewind(seconds: Double = SKIP_SECONDS, opts: ActionOptions = ActionOptions()): Unit =
        time.rewind(seconds, opts)

    public open suspend fun seekByPercentage(percent: Double, opts: ActionOptions = ActionOptions()): Unit =
        time.seekByPercentage(percent, opts)

    public open fun buffered(): Double = time.buffered()

    public open fun playbackRate(): Double = time.playbackRate()

    // What a speed menu offers. Fixed rather than engine-reported: the engines
    // disagree about what they accept and clamp what they do not, so asking
    // them would give a different menu per platform for no gain.
    public open fun playbackRates(): List<Double> = time.playbackRates()

    public open suspend fun playbackRate(rate: Double, opts: ActionOptions = ActionOptions()): Unit =
        time.playbackRate(rate, opts)

    // ── Volume ───────────────────────────────────────────────────────────────

    public open fun volume(): Int = volume.volume()

    public open suspend fun volume(level: Int, opts: ActionOptions = ActionOptions()): Unit =
        volume.volume(level, opts)

    public open suspend fun mute(opts: ActionOptions = ActionOptions()): Unit = volume.mute(opts)

    public open suspend fun unmute(opts: ActionOptions = ActionOptions()): Unit = volume.unmute(opts)

    public open suspend fun toggleMute(opts: ActionOptions = ActionOptions()): Unit = volume.toggleMute(opts)

    // A keyboard or a remote sends a nudge, not a level: the step lives here so
    // every surface moves the volume by the same amount.
    public open suspend fun volumeUp(step: Int = VOLUME_STEP, opts: ActionOptions = ActionOptions()): Unit =
        volume.volumeUp(step, opts)

    public open suspend fun volumeDown(step: Int = VOLUME_STEP, opts: ActionOptions = ActionOptions()): Unit =
        volume.volumeDown(step, opts)

    // ── Queue ────────────────────────────────────────────────────────────────

    public open fun queue(): List<PlaylistItem> = queue.queue()

    public open fun queue(items: List<PlaylistItem>): Unit = queue.queue(items)

    public open fun item(): PlaylistItem? = queue.item()

    public open fun index(): Int = queue.index()

    // What plays next and what played before, without moving to either. A
    // chrome showing "up next" needs the answer and must not change the queue
    // to get it.
    public open fun peekNext(): PlaylistItem? = queue.peekNext()

    public open fun peekPrevious(): PlaylistItem? = queue.peekPrevious()

    // One-based, matching the contract. Zero would be an off-by-one that only
    // shows up on the first item.
    public open fun seekToIndex(position: Int): Unit = queue.seekToIndex(position)

    // The queue's own operations, reachable from the player.
    //
    // They have existed on the controller since it was written and stopped
    // there, which meant a consumer holding a player could read the queue and
    // not change it — and the only workaround is reaching for `queue` the
    // property, which is an internal name that happens to be public.
    public open fun queueLength(): Int = queue.queueLength()

    public open fun queueIndexOf(id: String): Int = queue.queueIndexOf(id)

    public open fun queueAppend(items: List<PlaylistItem>): Unit = queue.queueAppend(items)

    public open fun queuePrepend(items: List<PlaylistItem>): Unit = queue.queuePrepend(items)

    public open fun queueInsert(items: List<PlaylistItem>, index: Int): Unit = queue.queueInsert(items, index)

    public open fun queueRemove(id: String): Unit = queue.queueRemove(id)

    public open fun queueRemoveAt(index: Int): Unit = queue.queueRemoveAt(index)

    public open fun queueMove(from: Int, to: Int): Unit = queue.queueMove(from, to)

    public open fun queueClear(): Unit = queue.queueClear()

    public open fun queueShuffle(): Unit = queue.queueShuffle()

    public open fun queueSort(comparator: Comparator<PlaylistItem>): Unit = queue.queueSort(comparator)

    // What has already played. It is a history store the host fills, not a
    // second queue: nothing here moves the cursor, and the player never writes
    // to it on its own. A player that auto-appended would be deciding what
    // "already played" means — skipped past, watched to the end, abandoned
    // halfway — and that is the host's question, not the library's.
    public open fun backlog(): List<PlaylistItem> = queue.backlog()

    public open fun backlog(items: List<PlaylistItem>): Unit = queue.backlog(items)

    public open fun backlogAppend(items: List<PlaylistItem>): Unit = queue.backlogAppend(items)

    public open fun backlogRemove(id: String): Unit = queue.backlogRemove(id)

    public open fun backlogClear(): Unit = queue.backlogClear()

    public open suspend fun item(id: String, autoplay: Boolean = false): Unit = queue.item(id, autoplay)

    public open suspend fun playItem(id: String): Unit = queue.playItem(id)

    // Replace the queue and start playing, in one call.
    //
    // What a "play this album" button does. Empty is a no-op rather than an
    // error: a caller handing over a filtered list that came back empty has
    // nothing to play, and clearing the queue would be a worse answer than
    // doing nothing.
    //
    // Does not stop what is playing first. The load interrupts it, and a caller
    // that needs a clean teardown calls stop() itself — which is the rarer case
    // and the one worth being explicit about.
    public open suspend fun playNow(items: List<PlaylistItem>, startId: String? = null) {
        if (items.isEmpty()) return
        queue.queue(items)
        queue.playItem(startId ?: items.first().id)
    }

    // Fetch a playlist and make it the queue.
    //
    // The parser is required, which the web's is not: it defaults to JSON.parse
    // there because a JavaScript object needs no type. Here PlaylistItem is an
    // interface and core cannot deserialize into one — only the host knows what
    // its items are. Asking for the parser is honest about that; a default that
    // could not work would be a method that throws at runtime for everyone.
    public open suspend fun loadQueue(url: String, parser: (String) -> List<PlaylistItem>) {
        context.emit(CoreEvents.PlaylistResolving, PlaylistResolvingPayload(url))

        val body: String = try {
            fetchPlaylist(url)
        } catch (failure: PlayerError) {
            reportPlaylistFailure(failure)
            throw failure
        }

        val items: List<PlaylistItem> = try {
            parser(body)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // A playlist that arrived and could not be read is a different
            // problem to one that never arrived: retrying will not help, and a
            // consumer showing "check your connection" would be wrong.
            val error = PlayerError(
                code = CoreErrorCodes.PLAYLIST_PARSE_ERROR,
                scope = ErrorScope.core(),
                message = "The playlist at $url could not be read: ${failure.message}",
                cause = failure,
            )
            reportPlaylistFailure(error)
            throw error
        }

        queue.queue(items)
        context.emit(CoreEvents.PlaylistReady, PlaylistReadyPayload(items.size.toDouble()))
    }

    private suspend fun fetchPlaylist(url: String): String {
        val response: FetchResponse = fetch(url, FetchOptions())
        if (response.status !in OK_STATUS) {
            throw PlayerError(
                code = CoreErrorCodes.PLAYLIST_FETCH_ERROR,
                scope = ErrorScope.core(),
                message = "The playlist at $url returned ${response.status}.",
                // The status goes in the bag so a caller can ask isHttp(5)
                // without parsing this sentence back apart.
                context = mapOf("httpStatus" to response.status),
            )
        }
        return response.body
    }

    // Both events, because they answer different questions. playlistResolveError
    // is what a caller awaiting this playlist is listening for; error is what a
    // consumer's single failure surface is listening for, and it should not have
    // to subscribe to every specific failure to know something went wrong.
    private fun reportPlaylistFailure(error: PlayerError) {
        val payload = PlayerErrorEvent(
            code = error.code,
            message = error.message.orEmpty(),
            severity = error.severity,
            scope = error.scope,
            suggestion = error.suggestion,
            context = error.context,
        )
        context.emit(CoreEvents.PlaylistResolveError, payload)
        context.emit(CoreEvents.Error, payload)
        report(error)
    }

    // ── Modes and state ──────────────────────────────────────────────────────

    // Bits per second, from whatever the host injected. Zero until something
    // does: the library cannot measure a connection and a guessed number would
    // make adaptation decide on a measurement that never happened.
    // Descriptors, never indices. Empty when the engine cannot report a ladder,
    // which is the honest answer for an audio-only backend rather than a menu
    // with nothing in it.
    public open fun qualityLevels(): List<QualityLevel> = video?.qualityLevels().orEmpty()

    public open fun quality(): QualityLevel? = video?.quality()

    // Null is automatic. A descriptor pins one rung, and what that means differs
    // per engine — an exact track on two of them and a ceiling on the third —
    // which is recorded in the divergence gate rather than smoothed over here.
    public open fun quality(level: QualityLevel?) {
        video?.quality(level)
    }

    // Whether a rung was chosen or the engine is adapting. A menu showing a
    // committed selection the viewer never made is worse than showing none.
    public open fun qualityMode(): QualityMode =
        if (video?.quality() == null) QualityMode.AUTO else QualityMode.MANUAL

    // Tracks, by the thing itself rather than by its place in a list. An
    // audio-only engine reports none rather than throwing, the same as the
    // ladder does.
    public open fun audioTracks(): List<AudioTrack> = video?.audioTracks().orEmpty()

    public open fun audioTrack(): AudioTrack? = video?.audioTrack()

    public open fun audioTrack(track: AudioTrack) {
        video?.audioTrack(track)
    }

    // subtitles() and subtitle(), not subtitleTracks() and subtitleTrack().
    //
    // The backend port uses the longer names because there it sits beside
    // audioTrack and the symmetry helps; the player surface uses the contract's,
    // because a consumer reading a name in the web docs has to find it here. A
    // renamed method is a divergence a conformance gate cannot forgive and a
    // developer cannot search for.
    public open fun subtitles(): List<SubtitleTrack> = video?.subtitleTracks().orEmpty()

    public open fun subtitle(): SubtitleTrack? = video?.subtitleTrack()

    // Null turns captions off, which is a selection a viewer makes rather than
    // an error — and it is the one every engine spells differently underneath.
    public open fun subtitle(track: SubtitleTrack?) {
        video?.subtitleTrack(track)
    }

    public open fun bandwidth(): Int = bandwidth.bandwidth()

    public open fun bandwidthEstimator(): (() -> Int)? = bandwidth.bandwidthEstimator()

    public open fun bandwidthEstimator(fn: (() -> Int)?): Unit = bandwidth.bandwidthEstimator(fn)

    public open fun state(): PlayerState = state.state()

    // The single field a chrome asks for most, without unpacking the snapshot.
    // The web has it for the same reason.
    public open fun playState(): PlayState = state.state().playState

    public open val stateFlow: StateFlow<PlayerState> get() = state.stateFlow

    // The state fields a chrome binds individually rather than through the
    // whole snapshot. Each is the same value state() carries; having both is the
    // contract's shape, and a chrome that only wants the volume icon should not
    // have to take a snapshot to get it.
    public open fun volumeState(): VolumeState = state.volumeState()

    public open fun setupState(): SetupState = state.state().setupState

    public open fun repeatState(): RepeatState = state.repeatState()

    public open suspend fun repeatState(value: RepeatState, opts: ActionOptions = ActionOptions()): Unit =
        state.repeatState(value, opts)

    public open fun shuffleState(): ShuffleState = state.shuffleState()

    public open suspend fun shuffleState(value: ShuffleState, opts: ActionOptions = ActionOptions()): Unit =
        state.shuffleState(value, opts)

    // ── Plugins ──────────────────────────────────────────────────────────────

    // Returns the player so registrations chain, which is how a host reads:
    // build it, add what it needs, set it up.
    public open suspend fun <O : Any> addPlugin(plugin: Plugin<O>, opts: O? = null): ComposedPlayer {
        plugins.register(plugin, opts)
        return this
    }

    public open fun removePluginById(id: String): Unit = plugins.remove(id)

    // By instance, because that is what a caller holds after adding one. The id
    // is on the plugin, so asking for it back would be asking the caller to
    // unpack something it already handed over.
    public open fun removePlugin(plugin: Plugin<*>): Unit = plugins.remove(plugin.id)

    // A plugin author's first question after adding one is how to get it back,
    // and the registry could always answer — the player just never asked. By id
    // rather than by type: a type lookup cannot tell two instances of the same
    // plugin apart, and an id is what the manifest already guarantees is unique.
    public open fun getPlugin(id: String): Plugin<*>? = plugins.getById(id)

    public open fun pluginList(): List<Plugin<*>> = plugins.plugins()

    // Same lookup as getPlugin, under the contract's name for it. Both exist
    // because both are in every consumer's muscle memory from the web player,
    // and a native port that answered only to one would be a gotcha rather than
    // a port.
    public open fun getPluginById(id: String): Plugin<*>? = plugins.getById(id)

    // ── Title tokens ─────────────────────────────────────────────────────────

    // Which letters in a title mean something, and what.
    //
    // A per-library call, not a consumer one: the video player registers S and
    // E in its constructor, the music player its own. Merged rather than
    // replaced, so a plugin adding one letter does not silently drop the
    // library's.
    public open fun registerTitleTokens(tokens: Map<String, String>) {
        titleTokens = titleTokens + tokens
    }

    // A title with its tokens filled in, in the viewer's language.
    //
    // A server that sent "Season 2" would be sending English to a Dutch viewer.
    // It sends the shape and this fills it in, which is the only way one stored
    // title works for everybody.
    public open fun formatTitle(text: String): String =
        TitleTokens.interpolate(text, titleTokens) { key, vars -> t(key, vars) }

    private var titleTokens: Map<String, String> = emptyMap()

    // ── Cue parsers ──────────────────────────────────────────────────────────

    // Adding a subtitle, lyric or sprite format without forking the player.
    //
    // Most-recently-registered wins, so overriding a built-in is just
    // registering one with the same job. atLowestPriority is how a built-in
    // seeds itself: anything a consumer adds later beats it without them
    // needing to know what was already there.
    public open fun registerCueParser(parser: CueParser, atLowestPriority: Boolean = false): Unit =
        cueParsers.register(parser, atLowestPriority)

    public open fun unregisterCueParser(id: String): Unit = cueParsers.unregister(id)

    public open fun resolveCueParser(url: String, contentType: String? = null): CueParser? =
        cueParsers.resolve(url, contentType)

    public val cueParsers: CueParserRegistry = CueParserRegistry()

    // ── Stream factories ─────────────────────────────────────────────────────

    // Teaching the player a protocol it does not know.
    //
    // Not the adaptive-bitrate path — the backend owns that. This is for the
    // ones a consumer has and this library does not: a DRM pipeline, a
    // peer-to-peer transport, an in-house segment format.
    //
    // Returns the player so registration chains, which is how a host wires
    // several at construction without naming the variable four times.
    public open fun registerStream(factory: StreamFactory, atLowestPriority: Boolean = false): ComposedPlayer {
        streamFactories.register(factory, atLowestPriority)
        return this
    }

    public open fun unregisterStream(id: String): ComposedPlayer {
        streamFactories.unregister(id)
        return this
    }

    public open fun streams(): List<String> = streamFactories.list()

    public open fun getStreamFactory(id: String): StreamFactory? = streamFactories.findById(id)

    public val streamFactories: StreamRegistry = StreamRegistry()

    public open fun enabledPlugins(): List<Plugin<*>> = plugins.enabledPlugins()

    public open fun contributions(slot: ChromeSlot): List<ContributionBinding> = plugins.contributions(slot)

    // ── PluginHost ───────────────────────────────────────────────────────────

    override val rootLogger: Logger get() = logger

    override val rootStorage: Storage get() = storage

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = context.on(key, fn)

    override fun <T> emit(key: EventKey<T>, data: T): Unit = context.emit(key, data)

    override suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = context.dispatchBefore(key, data)

    // No transport at this layer, and no honest default for one. A NotImplemented
    // that names the feature is better than a stub returning an empty 200, which
    // a plugin cannot tell from a server that answered.
    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse =
        fetcher?.fetch(url, opts)
            ?: throw NotImplementedError("This player was built without an HTTP transport.", "fetch")

    override fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel =
        throw NotImplementedError("This player was built without a realtime transport.", "websocket")

    // Falls back to the key so an untranslated string is visibly a key rather
    // than an empty label.
    override fun t(namespacedKey: String, vars: Map<String, String>): String =
        translator?.t(namespacedKey, vars) ?: namespacedKey

    // The rest of the translator, reachable through the player.
    //
    // A plugin adds its strings and takes them away again when it leaves, and
    // it has the player, not the translator. Routing through here is also what
    // makes the port optional: every one of these is a no-op without a
    // translator injected, so a host that has its own i18n system does not have
    // to satisfy an interface it will never use.
    public open fun language(): String = translator?.language() ?: UNTRANSLATED

    public open suspend fun language(lang: String) {
        translator?.language(lang)
    }

    public open fun addTranslations(bundle: Translations) {
        translator?.addTranslations(bundle)
    }

    public open fun removeTranslations(prefix: String, lang: String? = null) {
        translator?.removeTranslations(prefix, lang)
    }

    public open fun translation(lang: String, key: String): String? = translator?.translation(lang, key)

    public open fun translation(lang: String, key: String, value: String) {
        translator?.translation(lang, key, value)
    }

    override fun report(error: PlayerError) {
        logger.error("${error.code}: ${error.message}")
    }
}

// Says nothing. The default because a library that prints to a host's console
// uninvited is a library people wrap to shut it up.
public object SilentLogger : Logger {
    override fun error(message: String, vararg args: Any?): Unit = Unit
    override fun warn(message: String, vararg args: Any?): Unit = Unit
    override fun info(message: String, vararg args: Any?): Unit = Unit
    override fun debug(message: String, vararg args: Any?): Unit = Unit
    override fun child(scope: String): Logger = this
}

// Enough for a player with no persistence wired: plugins that store preferences
// keep them for the session and lose them on restart, which is a real behaviour
// a host can choose rather than a crash.
public class InMemoryStorage : Storage {
    private val entries: MutableMap<String, String> = mutableMapOf()

    override suspend fun get(key: String): String? = entries[key]

    override suspend fun set(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}
