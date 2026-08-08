// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.KIT_VERSION
import tv.nomercy.player.core.cues.registerBuiltIns
import tv.nomercy.player.core.devtools.EventFirehose
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.NetworkError
import tv.nomercy.player.core.errors.resourceError
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.NotImplementedError
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.ScopeKind
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.net.AuthFetch
import tv.nomercy.player.core.net.FetchSignal
import tv.nomercy.player.core.events.FetchStartPayload
import tv.nomercy.player.core.events.FetchRetryPayload
import tv.nomercy.player.core.events.FetchCompletePayload
import tv.nomercy.player.core.events.BeforeAudioTrackPayload
import tv.nomercy.player.core.events.BeforeLanguagePayload
import tv.nomercy.player.core.events.AudioTrackPreventedPayload
import tv.nomercy.player.core.events.LanguagePreventedPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.AudioTrackPayload
import tv.nomercy.player.core.events.SubtitlePayload
import tv.nomercy.player.core.events.AudioTrackStatePayload
import tv.nomercy.player.core.events.BeforeTransferPayload
import tv.nomercy.player.core.events.CastTarget
import tv.nomercy.player.core.events.TransferPreventedPayload
import tv.nomercy.player.core.events.AuthFailedPayload
import tv.nomercy.player.core.events.AuthRefreshedPayload
import tv.nomercy.player.core.events.CastStatePayload
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.events.BeforeSubtitlePayload
import tv.nomercy.player.core.events.SubtitlePreventedPayload
import tv.nomercy.player.core.events.PreventReason
import tv.nomercy.player.core.events.QualityStatePayload
import tv.nomercy.player.core.events.TransitionCancelledPayload
import tv.nomercy.player.core.events.PlaylistReadyPayload
import tv.nomercy.player.core.events.PlaylistResolvingPayload
import tv.nomercy.player.core.events.TimeState
import tv.nomercy.player.core.events.PlaybackMetrics
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.chapters.ChapterController
import tv.nomercy.player.core.events.ChaptersPayload
import tv.nomercy.player.core.events.ChapterPayload
import tv.nomercy.player.core.media.Chapter
import tv.nomercy.player.core.media.ChapterTrack
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.media.TitleTokens
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.ActionSource
import tv.nomercy.player.core.player.AudioTrackState
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
import tv.nomercy.player.core.ports.CastSender
import tv.nomercy.player.core.ports.AudioOutput
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
import tv.nomercy.player.core.ports.PreloadAsset
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
import tv.nomercy.player.core.ports.defaultStorage
import tv.nomercy.player.core.ports.TimeRange
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

// The word on a chapter the player invented to cover an unlabelled run-up. The
// web resolves the same key for the same filler, so the two read the same in
// every language a host has a table for.
private const val CHAPTER_FILLER_KEY = "core.chapters.untitled"

// Every 2xx. A playlist behind a redirect the fetcher already followed arrives
// as a 200; anything else did not arrive.
private val OK_STATUS = 200..299

// The reason a transition ends when a consumer swaps the strategy under it.
private const val STRATEGY_REPLACED = "strategy-replaced"

// The target a reclaim announces, so a before-listener sees one shape whichever
// direction the handoff goes.
private val LOCAL_TARGET = CastTarget(id = "local", name = "This device")

private const val NO_CAST_SENDER = "no-cast-sender"

// One namespace for the whole library, with each plugin's keys prefixed
// underneath it by NamespacedStorage. Not the player id: two players in one
// process are the phone and its cast session, and a viewer's subtitle
// language is theirs rather than that session's.
private const val NAMESPACE = "player"

// The platform's store when it has one, a map when it does not.
//
// A player must not fail to CONSTRUCT because a preferences backend is
// unavailable. java.util.prefs needs a writable backing store and has none in a
// headless container — which turned every test that builds a player red the
// moment the default stopped being in-memory, on a change whose entire point
// was that nothing inside one process could see it.
//
// Losing preferences is a degraded player. Failing to build one is no player.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun persistentStorageOrMemory(): Storage = try {
    defaultStorage(NAMESPACE)
} catch (unavailable: Throwable) {
    InMemoryStorage()
}

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

// Large, and it has to be. The contract is a hundred and thirty methods and this
// is the one object that has them: a consumer holds a player, and a facade split
// into four would mean looking in four places for a method the docs name once.
//
// The size is forwarding, not logic. Every method here hands off to a controller
// that owns the behaviour, and those are the small single-purpose pieces — this
// is the door they are behind.
@Suppress("TooManyFunctions", "LargeClass")
// The reason a refusal carries when the listener gave none. The same string the
// reference uses, because a consumer matching on it should not have to know
// which platform refused.
private const val LISTENER_PREVENTED: String = "listener-prevented"

// The web's player has this surface, and a port that split it would be a
// different library wearing the same name: every one of these methods is on
// `nmplayer()` in the TypeScript, and a consumer moving over expects to find
// them in one place. The seams that CAN move already have — the controllers
// this class delegates to are exactly that split, and what remains is the
// facade over them.
@Suppress("LargeClass", "TooManyFunctions")
public open class ComposedPlayer(
    backend: MediaBackend? = null,
    private val logger: Logger = SilentLogger,
    // The platform's own store, not a map that dies with the process.
    //
    // defaultStorage has had working actuals on Android, Apple and the JVM for
    // as long as the port has existed, with tests, and was called by nothing
    // outside them: the default here was InMemoryStorage, so every plugin's
    // storage — equaliser presets, the viewer's subtitle language, anything a
    // plugin wrote — was thrown away at exit.
    //
    // That is invisible from inside a process. A save-then-restore test passes
    // against a map, the row appears in the plugin tree, and the only thing
    // that can see the defect is relaunching the app and finding the choice
    // gone. Which is the one thing a preferences plugin exists to do.
    // Null means the platform's own store. Nullable rather than defaulted so a
    // subclass can forward the choice without having to reach the private
    // helper that resolves it — NMVideoPlayer could not, so it hid the parameter
    // and every consumer shared one file.
    storage: Storage? = null,
    private val translator: Translator? = null,
    // Told when a key resolved to itself, which is the only way a team finds out
    // which strings are untranslated in a build a viewer is already running.
    //
    // Beside the translator rather than in PlayerConfig, because it is the same
    // decision: a host that injected no translator wants to hear about every key
    // it is being asked for, and one that did wants to hear about the misses.
    // The fallback is unchanged and stays the visible answer: the key itself
    // reaches the UI, because a blank button is not a bug report.
    private val onMissingTranslation: ((key: String, vars: Map<String, String>) -> Unit)? = null,
    // Supplied by the chrome, which is the only layer that can reach a platform
    // accessibility API. Absent means the player says nothing.
    private val announcer: Announcer? = null,
    // Whatever owns a remote session, when something does. Core cannot cast: the
    // protocols are a Google SDK, an AirPlay route and NoMercy's own Connect
    // session, and none belong in a library whose job is deciding what plays.
    //
    // A constructor parameter and not a setter, even though the object that
    // implements it is a plugin added afterwards. The reference has no such
    // seam at all — its cast plugin holds the session and calls the player back
    // — so a setCastSender here would be a method the contract does not name,
    // which the surface conformance test rejects and should. A consumer builds
    // the controller and the plugin, hands the plugin in here, and adds it.
    private val castSender: CastSender? = null,
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
) : PluginHost, EventFirehose {

    public val context: PlayerContext = PlayerContext(backend = backend)

    public val queue: QueueController = QueueController(context)
    public val transport: TransportController = TransportController(context, queue)
    public val volume: VolumeController = VolumeController(context)
    public val time: TimeController = TimeController(context, queue, transport, clock)
    public val state: StateController = StateController(context, queue, time)
    // Protected rather than private: a per-library player has its own event
    // handlers that need to start suspending work — a segment window seeking
    // back on a loop, a crossfade handing over — and a second scope built beside
    // this one would outlive the player that owns the work.
    // With a handler, because a library's scope must not be able to kill its
    // host. A bare SupervisorJob sends an uncaught failure to the thread's
    // default handler, and on a Compose Desktop app that is the thread the UI
    // is drawn on — logic keeps running and nothing paints again, which reads
    // as a dead window rather than as a coroutine that threw.
    //
    // Reported through the same error channel a listener throw uses, so it
    // arrives somewhere a consumer is already looking.
    protected val playerScope: CoroutineScope = scope ?: CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, failure ->
            context.emit(
                CoreEvents.Error,
                PlayerError(
                    code = CoreErrorCodes.CLEANUP_FAILED,
                    scope = ErrorScope.core(),
                    message = "A player coroutine failed: ${failure.message ?: failure::class.simpleName}",
                    cause = failure,
                ).asEvent(),
            )
        },
    )

    // Whether dispose may cancel it. A scope handed in belongs to the caller.
    private val ownsScope: Boolean = scope == null

    public val plugins: PluginRegistry = PluginRegistry(this, KIT_VERSION, playerScope)

    // The contract's `plugins()` — the registered plugins themselves.
    //
    // The registry above answers to the same name and is the richer thing, so
    // this looked done. It is not the same call: a consumer following the web
    // docs writes `player.plugins()` expecting a list and, in Kotlin, the
    // property shadowed nothing and the call did not compile.
    public open fun plugins(): List<Plugin<*>> = plugins.plugins()

    public val lifecycle: LifecycleController = LifecycleController(context, plugins, ::report)

    // What the engine reports, turned into what the player says. Without it
    // the controllers drive the engine and nothing listens to it come back.
    // With the scope and the clock, which is what lets it retry rather than only
    // report. A bridge given neither still works and gives up on the first
    // failure, which is what every platform did before this.
    public val bridge: BackendBridge = BackendBridge(context, playerScope, clock)

    public val bandwidth: BandwidthController = BandwidthController(context)

    public val metrics: MetricsController = MetricsController(clock)

    // The screen, the surface and the connection. Three host-settable options
    // that did nothing until setup wired them.
    private val policy: PolicyController = PolicyController(context, platform, transport, playerScope)

    // Reads the strategies through accessors rather than holding them, because
    // setPreloadStrategy and setTransitionStrategy swap them at runtime and a
    // captured reference would keep consulting the one the consumer replaced.
    private val preloading: PreloadController = PreloadController(
        ctx = context,
        queue = queue,
        scope = playerScope,
        preload = ::preloadStrategy,
        transition = ::transitionStrategy,
        warm = ::warmAsset,
    )

    // Null-transport hosts are the ordinary case, so a preload that cannot fetch
    // is not an error — the strategy still names what it wanted and the events
    // still fire. Only a host that wired a fetcher pays for the request.
    private suspend fun warmAsset(asset: PreloadAsset) {
        if (fetcher == null) return
        fetch(asset.url, FetchOptions(method = "HEAD"))
    }

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

        // The counters, connected to the events that move them.
        //
        // Nothing anywhere in the trio called recordMetric, so the sampler
        // faithfully published six zeros every interval for the life of every
        // session — a whole telemetry path built, tested and wired to nothing,
        // and a testbed log filled with `PlaybackMetrics(droppedFrames=0.0,
        // totalFrames=0.0, ...)` on repeat.
        //
        // These three are countable here because core already has the events.
        // droppedFrames, totalFrames and bitrate come from the engine and are
        // the backend's to report.
        context.on(CoreEvents.BackendWaiting) { metrics.onWaitingStarted() }
        context.on(CoreEvents.BackendStalled) { metrics.onWaitingStarted() }
        context.on(CoreEvents.Playing) { metrics.onWaitingEnded() }
        context.on(CoreEvents.FirstFrame) { metrics.onFirstFrame() }


        // Resuming re-arms the countdown even when the resume came from
        // somewhere no UI saw it — a headphone button, a car head unit.
        // Otherwise controls shown during a pause stay up for the rest of the
        // film: the countdown expired harmlessly while paused, and nothing
        // would arm it again.
        context.on(CoreEvents.Play) { activity.onPlaybackResumed() }

        // The other half of the chapter seam. Chapters routinely resolve before
        // the engine has read a duration, and there is no end to fill up to until
        // it does — so the run-up filler cannot exist yet at ingest and has to be
        // derived again once a length lands or is corrected.
        //
        // Only on a change, unlike ingest. Duration arrives on every item and can
        // be re-reported for the same one, and announcing an unchanged list each
        // time would rebuild every chapter menu on a tick that moved nothing.
        context.on(CoreEvents.Duration) { seconds ->
            if (chapterController.durationChanged(seconds)) publishChapters()
        }

        // Which chapter is playing, announced when it changes.
        //
        // CoreEvents.Chapter was declared and NOTHING EMITTED IT, so a chrome
        // showing the current chapter title had one read — chapter() — and no
        // way to learn it had moved. The reference announces this, and the
        // difference is visible the moment a viewer skips a chapter: the web
        // fires chapter between beforeSeek and seek, and this fired nothing.
        //
        // On a change only. Time updates arrive continuously and re-announcing
        // the same chapter on every tick would redraw a title that did not move.
        context.on(CoreEvents.Time) { announceChapterIfMoved() }
    }

    // The last chapter announced, so a tick inside the same one says nothing.
    private var announcedChapterStart: Double? = null

    private fun announceChapterIfMoved() {
        val current: Chapter? = chapterTrack.at(time())
        if (current?.startTime == announcedChapterStart) return

        announcedChapterStart = current?.startTime
        if (current == null) return

        context.emit(
            CoreEvents.Chapter,
            ChapterPayload(
                index = chapterTrack.chapters.indexOf(current).toDouble(),
                title = current.title,
            ),
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public open suspend fun setup(config: PlayerConfig = PlayerConfig()) {
        // Taken here rather than read from the config on every call, so a later
        // baseUrl(url) is not silently overruled by the value setup was given.
        configuredBaseUrl = config.baseUrl
        configuredImageBaseUrl = config.baseImageUrl
        configuration = config
        context.mutationGuards = config.mutationGuards
        // The registry is what knows which plugin declared what, and the context
        // is what raises the advisory. Wired here rather than at construction
        // because a plugin registered later has to be seen: the lambda reads the
        // live registry on every guarded call, so it always reflects what is
        // installed and enabled right now.
        context.advisories = plugins::advisories
        activity = ActivityController(context, playerScope, config.inactivityMs)
        // Pushed rather than read back from options() when the engine needs it: the
        // decision is taken on a tracks change, which can arrive before anything
        // would have thought to ask, and a backend holding the answer cannot be
        // caught mid-load without one.
        video?.hdrOnSdrFallback(config.hdrOnSdr)

        // Before lifecycle.setup, which is what announces `ready`. A host that
        // queued an item and pressed play from its ready handler would otherwise
        // be playing through wiring that had not been installed yet.
        time.configure(config)
        policy.setup(config)
        preloading.setup(config)
        metrics.startSampling(playerScope, config.metricsIntervalMs) {
            context.emit(CoreEvents.PlaybackMetrics, it)
        }

        lifecycle.onCleanup("policies", policy::dispose)
        lifecycle.onCleanup("preload", preloading::dispose)
        lifecycle.onCleanup("metrics", metrics::stopSampling)
        lifecycle.onCleanup("activity") { activity.dispose() }

        lifecycle.setup(config)

        // Controls start visible: the viewer just started a player, which is
        // activity. This arms the countdown that fades them once playback runs.
        activity.bumpActivity()
    }

    public open fun ready(): Deferred<Unit> = lifecycle.ready()

    public open suspend fun dispose(opts: ActionOptions = ActionOptions()) {
        lifecycle.dispose(opts)

        // The scope dies with the player, and nothing was cancelling it.
        //
        // Every launch on it — an auto-advance, a default-track selection, a
        // sidecar fetch — outlived the player that started it, so work carried
        // on against a disposed one and a failure surfaced wherever that
        // coroutine happened to land. In tests that is the NEXT test, as
        // "uncaught exceptions before the test started"; in an app it is a
        // handler firing after the screen it belonged to is gone.
        //
        // Only when this player owns it. A scope passed in belongs to the
        // caller and cancelling it would take down whatever else is on it.
        if (ownsScope) playerScope.cancel()
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

    // ── Casting ──────────────────────────────────────────────────────────────

    // Hand playback to something else in the room, or take it back.
    //
    // A null target reclaims: whatever the remote had got to becomes the local
    // position, so the viewer who walked out of the living room picks up where
    // the television was rather than where they left this device.
    //
    // Refusable. A listener that vetoes a transfer is the seam a chrome uses to
    // ask "you have unsaved edits, still cast?" and mean it.
    public open suspend fun transferTo(target: CastTarget?): Boolean {
        val outcome: BeforeDispatchResult<BeforeTransferPayload> =
            context.dispatchBefore(CoreEvents.BeforeTransfer, BeforeTransferPayload(target ?: LOCAL_TARGET))
        if (outcome.prevented) {
            context.emit(CoreEvents.TransferPrevented, TransferPreventedPayload(outcome.reason.orEmpty()))
            return false
        }

        val sender: CastSender = castSender ?: run {
            // Nothing owns a session, so nothing can be handed anywhere. False
            // rather than a throw: a chrome offering a cast button on a build
            // without the plugin should find out by asking, not by crashing.
            context.emit(CoreEvents.TransferPrevented, TransferPreventedPayload(NO_CAST_SENDER))
            return false
        }

        return if (target == null) reclaimFrom(sender) else handOff(sender, outcome.data.target)
    }

    // Pause before handing over, not after. Two devices playing the same thing
    // a second apart is the audible failure of getting this order wrong.
    private suspend fun handOff(sender: CastSender, target: CastTarget): Boolean {
        castState(CastState.CONNECTING)
        // Named, because this one knows the answer. An unsourced pause reaches a
        // listener as PlaySource(source=null), which reads as "nobody knows who
        // asked" — and a plugin dimming a lamp on a viewer's pause would dim it
        // for a handoff to the television, which is the opposite of the intent.
        transport.pause(ActionOptions(source = ActionSource.PLATFORM))

        val accepted: Boolean = sender.transfer(target, item(), time())
        castState(if (accepted) CastState.CONNECTED else CastState.AVAILABLE)
        return accepted
    }

    private suspend fun reclaimFrom(sender: CastSender): Boolean {
        val remotePosition: Double? = sender.reclaim()
        castState(CastState.DISCONNECTED)

        // Only when the remote knew. A null position means it could not say, and
        // seeking to zero would restart the item on the way home.
        remotePosition?.let { time.time(it) }
        return true
    }

    // ── Audio output ─────────────────────────────────────────────────────────

    // Where sound can go, and where it is going.
    //
    // Empty when the platform cannot route audio, which is not the same as
    // having no outputs — but it is the same to a caller deciding whether to
    // draw a picker, and the distinction is available through platform() for
    // one that needs it.
    public open suspend fun audioOutputs(): List<AudioOutput> =
        platform.audioOutput?.outputs().orEmpty()

    public open suspend fun audioOutput(): AudioOutput? = platform.audioOutput?.current()

    // The writer half of the pair above, not a differently-named method.
    //
    // This was `selectAudioOutput(id)`, which is the contract's name for
    // something else entirely: on the web `selectAudioOutput()` takes nothing
    // and opens the browser's device picker. Routing to a known device is
    // `audioOutput(id)` there, so a consumer following the docs called a method
    // this library did not have and found one whose name promised a picker.
    //
    // False when there was nothing to ask or the platform refused.
    //
    // Not a throw: iOS routes audio by policy and treats a preference as a
    // suggestion, so "your choice did not take" is an ordinary outcome a caller
    // has to handle rather than an exceptional one.
    public open suspend fun audioOutput(id: String): Boolean =
        platform.audioOutput?.select(id) ?: false

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

    /**
     * Warm an item now, through the same strategy the automatic pass uses.
     *
     * The reference's AutoAdvancePlugin.preloadNext is this: a host deciding
     * for itself when the next thing is worth fetching, instead of waiting for
     * the lead-seconds window.
     */
    public open suspend fun preloadNow(item: PlaylistItem) {
        preloading.preloadNow(item)
    }

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

    // holdChrome and releaseChrome are deliberately NOT forwarded here. The
    // reference puts them on the desktop-ui PLUGIN rather than on the player, and
    // the contract gate caught the first attempt at this: core inventing a method
    // the web's core does not have is the same defect as core missing one. A
    // chrome reaches the count through `activity`, which is public.

    // ── Instrumentation ──────────────────────────────────────────────────────

    public open fun metrics(): PlaybackMetrics = metrics.metrics()

    // On the hot list, so guarded only when a consumer asks for it. A metric
    // records per sample and a dispatch per sample is what the hot list exists
    // to keep off by default.
    public open fun recordMetric(metric: Metric, value: Double) {
        if (context.guardMutation("recordMetric", listOf(metric, value))) metrics.recordMetric(metric, value)
    }

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

    // Where data actually is, and where the playhead may go.
    //
    // Ranges rather than a frontier, because a seek backwards into evicted
    // buffer is the case one number cannot describe: it says data reaches to
    // 400s while the first 200 have been dropped, and a scrubber drawn from it
    // promises a jump that stalls.
    public open fun bufferedRanges(): List<TimeRange> = time.bufferedRanges()

    public open fun seekable(): List<TimeRange> = time.seekable()

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

    // Through the controller, which is the seam that fills the run-up to the
    // first chapter and re-fills it when a duration arrives.
    //
    // This built a ChapterTrack straight from the list, and the controller was
    // reachable from nothing but its own test. A scan starting its first chapter
    // partway in left the opening minutes inside no chapter at all: chapter() was
    // null over a cold open, a chrome showing the current title showed nothing,
    // and the whole of the web's gap-fill had no way in.
    public open fun chapters(list: List<Chapter>) {
        chapterController.ingest(list)
        publishChapters()
    }

    // Always announced, even when the list is identical to the one before it.
    //
    // "Chapters became available" rather than "the list changed": an item
    // reloaded after an error resolves the same chapters it had, and a chrome
    // that cleared its menu on the error would never get them back if an
    // identical list were treated as nothing to say. The web's `alwaysEmit`.
    private fun publishChapters() {
        chapterTrack = ChapterTrack(chapterController.chapters())
        context.emit(CoreEvents.Chapters, ChaptersPayload(chapterTrack.chapters))
    }

    public open fun chapter(): Chapter? = chapterTrack.at(time())

    // The writer half. The web's `chapter(idx)` hands straight to
    // `seekToChapter`, and so does this — the pair exists because a chrome
    // reading `chapter()` to draw a menu writes back through the same name.
    public open suspend fun chapter(index: Int, opts: ActionOptions = ActionOptions()) {
        seekToChapter(index, opts)
    }

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

    // Resolved per call rather than captured once, so a player whose language
    // changes after the chapters arrived re-fills into the new language on the
    // next duration tick rather than keeping the old word.
    private val chapterController: ChapterController = ChapterController(::chapterFillerTitle)

    // The host's word for an invented chapter, and the kit's when it has none.
    //
    // t() answers with the key itself when nothing translated it. That is right
    // for a tooltip, where a developer should see the wiring is missing, and
    // wrong for a chapter title, where a viewer would read
    // 'core.chapters.untitled' in their menu. Native core ships no table of its
    // own the way the web kit does, so the unresolved case is the ordinary one
    // here rather than a misconfiguration.
    private fun chapterFillerTitle(): String {
        val translated: String = t(CHAPTER_FILLER_KEY)

        return if (translated == CHAPTER_FILLER_KEY) ChapterController.DEFAULT_FILLER_TITLE else translated
    }

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

    // Also the PluginHost seam a plugin installed mid-playback reads to find
    // out what it missed. Same answer to both callers by construction.
    override fun item(): PlaylistItem? = queue.item()

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
    // Three throws for three distinguishable failures: the server refused it,
    // the transport could not ask, and it arrived unreadable. A consumer shows
    // a different message for each, so collapsing them loses the distinction
    // the error codes exist to carry.
    @Suppress("ThrowsCount")
    public open suspend fun loadQueue(url: String, parser: (String) -> List<PlaylistItem>) {
        context.emit(CoreEvents.PlaylistResolving, PlaylistResolvingPayload(url))

        val body: String = try {
            fetchPlaylist(url)
        } catch (failure: PlayerError) {
            reportPlaylistFailure(failure)
            throw failure
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // The transport threw rather than answering with a status. That is
            // not a playlist the server refused, it is one that could not be
            // asked for, and it used to escape as whatever the transport chose
            // to throw — an engine exception with no code on it at all.
            val error = resourceError(
                CoreErrorCodes.PLAYLIST_FETCH_FAILED,
                failure.message ?: "The playlist at $url could not be fetched.",
                mapOf("url" to url),
            )
            reportPlaylistFailure(error)
            throw error
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
        // The pipeline classifies the status now, so a 404 arrives here as
        // core:network/not-found rather than as a response with a number on it.
        // A caller awaiting a playlist wants the playlist code — that is what
        // this method has always thrown and what a consumer catches — so the
        // specific network code travels in the bag rather than replacing it.
        val response: FetchResponse = try {
            fetch(url, FetchOptions())
        }
        catch (failure: NetworkError) {
            throw PlayerError(
                code = CoreErrorCodes.PLAYLIST_FETCH_ERROR,
                scope = ErrorScope.core(),
                message = "The playlist at $url could not be fetched: ${failure.code}.",
                cause = failure,
                context = failure.context + mapOf("networkCode" to failure.code),
            )
        }

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
    //
    // The general channel comes from report, which is now the one place a
    // severity is turned into an event. Emitting it here as well delivered the
    // same failure to the same listener twice.
    private fun reportPlaylistFailure(error: PlayerError) {
        context.emit(CoreEvents.PlaylistResolveError, error.asEvent())
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

        // Announced, which it was not. audioTrack() directly below records the
        // choice and emits two events; this recorded nothing and emitted
        // nothing, and that asymmetry is the whole bug: a quality menu has no
        // way to learn a rung was picked, so it draws no selection however many
        // times a viewer chooses one.
        qualityChoice = if (level == null) QualityMode.AUTO else QualityMode.MANUAL
        context.emit(CoreEvents.QualityState, QualityStatePayload(qualityChoice.wire))
    }

    // What the VIEWER asked for, not what the engine happens to report.
    //
    // This read back through video?.quality(), and on an adaptive stream that is
    // always null — libVLC's demuxer picks its own representation and publishes
    // one track — so a manual pick read back as AUTO on the next recomposition
    // and the menu forgot it immediately.
    private var qualityChoice: QualityMode = QualityMode.AUTO

    // Whether a rung was chosen or the engine is adapting. A menu showing a
    // committed selection the viewer never made is worse than showing none.
    public open fun qualityMode(): QualityMode = qualityChoice

    // The writer half, by rung index, which is how the web spells it — null is
    // the web's `'auto'`. An index rather than a descriptor because that is
    // what a menu holds: the list it drew, and the row the viewer touched.
    public open fun qualityMode(index: Int?) {
        quality(index?.let { qualityLevels().getOrNull(it) })
    }

    // Tracks, by the thing itself rather than by its place in a list. An
    // audio-only engine reports none rather than throwing, the same as the
    // ladder does.
    public open fun audioTracks(): List<AudioTrack> = video?.audioTracks().orEmpty()

    public open fun audioTrack(): AudioTrack? = video?.audioTrack()

    // Two events, because they answer different questions and always have on
    // the web: `audioTrack` says WHICH, `audioTrackState` says whether anybody
    // chose it. Only the second was being emitted, so a consumer porting a
    // listener for the first — and the shipped preferences plugin, which is
    // what surfaced this — heard nothing at all while the track changed
    // underneath them.
    // Refusable, and suspend for the same reason the reference's writer returns
    // a promise: the before-hook can be answered by a suspending listener, so
    // the decision is not available synchronously. beforeAudioTrack and
    // audioTrackPrevented were both declared here and reachable from nowhere —
    // a listener vetoing a track change was never asked.
    public open suspend fun audioTrack(track: AudioTrack) {
        // The seam runs when there is an index to dispatch, which is the only
        // thing the reference's before-hook carries. It does not validate that
        // index against the list — a listener may rewrite it, and the rewritten
        // one is what gets applied — so neither does this.
        val requested: Double? = indexIn(audioTracks(), track)

        if (requested != null) {
            val outcome: BeforeDispatchResult<BeforeAudioTrackPayload> =
                context.dispatchBefore(CoreEvents.BeforeAudioTrack, BeforeAudioTrackPayload(id = requested))

            if (outcome.prevented) {
                context.emit(
                    CoreEvents.AudioTrackPrevented,
                    AudioTrackPreventedPayload(reason = outcome.reason ?: LISTENER_PREVENTED),
                )
                return
            }

            audioTracks().getOrNull(outcome.data.id.toInt())?.let { video?.audioTrack(it) }
        }
        else {
            video?.audioTrack(track)
        }

        audioTrackChoice = AudioTrackState.MANUAL
        context.emit(CoreEvents.AudioTrack, AudioTrackPayload(indexIn(audioTracks(), track)))
        context.emit(CoreEvents.AudioTrackState, AudioTrackStatePayload(AudioTrackState.MANUAL.token))
    }

    // Whether the audio track was chosen or fell out of the engine's defaults.
    //
    // The distinction a chrome needs to know whether to show a tick beside a
    // language: DEFAULT means the engine picked, which is usually the item's
    // first track and not a decision anyone made. Once a viewer chooses, the
    // choice should survive the next item rather than being re-decided by the
    // engine — and this is what a per-library player reads to do that.
    public open fun audioTrackMode(): AudioTrackState = audioTrackChoice

    // The writer half, by index into audioTracks(), which is how the web spells
    // it and what a language menu has to hand. Choosing marks the selection
    // MANUAL, which is the whole reason the reader exists.
    public open suspend fun audioTrackMode(index: Int) {
        audioTracks().getOrNull(index)?.let { audioTrack(it) }
    }

    private var audioTrackChoice: AudioTrackState = AudioTrackState.DEFAULT

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
    // Refusable, and suspend for the same reason the reference's writer returns
    // a promise: a before-listener can be answered by a suspending one, so the
    // decision is not available synchronously.
    //
    // beforeSubtitle and subtitlePrevented were both declared, both catalogued,
    // and emitted by nothing at all — a consumer following the reference to
    // veto a caption change had nothing to veto. Making this suspend is what
    // lets the hook be awaited; the trio is v0 with no published consumers, so
    // there is nobody to migrate.
    public open suspend fun subtitle(track: SubtitleTrack?) {
        // The payload carries the INDEX, as the reference's does — a listener
        // reading it gets the same number in both ecosystems, and it is what a
        // listener rewrites when it redirects a selection.
        val asked: Double? = track?.let { indexIn(subtitles(), it) }
        val decision: BeforeDispatchResult<BeforeSubtitlePayload> =
            context.dispatchBefore(CoreEvents.BeforeSubtitle, BeforeSubtitlePayload(asked))

        if (decision.prevented) {
            context.emit(
                CoreEvents.SubtitlePrevented,
                SubtitlePreventedPayload(reason = decision.reason ?: PreventReason.ListenerPrevented),
            )
            return
        }

        val chosen: SubtitleTrack? = decision.data.track?.let { subtitles().getOrNull(it.toInt()) }
        video?.subtitleTrack(chosen)
        context.emit(CoreEvents.Subtitle, SubtitlePayload(decision.data.track))
    }

    // Where the track sits in the list the viewer was shown, or null when the
    // engine no longer reports it.
    //
    // The payload carries a position because the web's does and a listener
    // ported across should not have to be rewritten. It is not what anything
    // here SELECTS by — selection is by descriptor throughout, for the reason
    // QualityLevel spells out — so this number is a label on an event, never an
    // identity anyone acts on.
    private fun <T> indexIn(list: List<T>, item: T): Double? =
        list.indexOf(item).takeIf { it >= 0 }?.toDouble()

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

    // Guarded, which is what the mutation surface is for: neither of these has
    // a before-event of its own, so this is the only place a plugin can object
    // to a repeat or shuffle change before it lands.
    public open suspend fun repeatState(value: RepeatState, opts: ActionOptions = ActionOptions()) {
        if (context.guardMutation("repeatState", listOf(value))) state.repeatState(value, opts)
    }

    public open fun shuffleState(): ShuffleState = state.shuffleState()

    public open suspend fun shuffleState(value: ShuffleState, opts: ActionOptions = ActionOptions()) {
        if (context.guardMutation("shuffleState", listOf(value))) state.shuffleState(value, opts)
    }

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
    public open fun registerCueParser(parser: CueParser<*>, atLowestPriority: Boolean = false): Unit =
        cueParsers.register(parser, atLowestPriority)

    public open fun unregisterCueParser(id: String): Unit = cueParsers.unregister(id)

    // Overrides the PluginHost default (null): this player has a real registry,
    // so a plugin asking through the host sees exactly what a consumer calling
    // this method directly sees.
    override fun resolveCueParser(url: String, contentType: String?): CueParser<*>? =
        cueParsers.resolve(url, contentType)

    // Seeded with LRC, subtitle VTT and sprite VTT. The web registers those at
    // setup; here they arrive with the player, so resolving one does not depend
    // on how far through its lifecycle it is.
    public val cueParsers: CueParserRegistry = CueParserRegistry().registerBuiltIns()

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

    // Resolved once, and NOT during construction.
    //
    // A `get()` calling the resolver would build a new store on every read and
    // two plugins would be looking at two of them. An eagerly initialised
    // property declared this far down the class is worse: anything that touches
    // `rootStorage` while the constructor is still running reads an
    // uninitialised field, which the JVM tolerates as null and Kotlin/Native
    // aborts on — the iOS testbed died at launch with SIGABRT through Kotlin's
    // terminate handler and no Swift frame in the trace.
    //
    // `by lazy` puts the resolution at first USE, which is after construction
    // however the properties are ordered.
    private val resolvedStorage: Storage by lazy { storage ?: persistentStorageOrMemory() }

    override val rootStorage: Storage get() = resolvedStorage

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = context.on(key, fn)

    // ── EventFirehose ────────────────────────────────────────────────────────

    // The web player inherits this from its emitter, so on('all') has always
    // been reachable there. Here the emitter is a field, and the only code that
    // wanted the firehose was reaching through context.emitter to get it. This
    // is that reach, on the surface where the web contract already puts it.
    override fun onAll(fn: (name: String, data: Any?) -> Unit): Subscription =
        context.emitter.onAll(fn)

    override fun <T> emit(key: EventKey<T>, data: T): Unit = context.emit(key, data)

    override suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = context.dispatchBefore(key, data)

    // No transport at this layer, and no honest default for one. A NotImplemented
    // that names the feature is better than a stub returning an empty 200, which
    // a plugin cannot tell from a server that answered.
    // Through the auth controller on the way out, which is the point of routing
    // a plugin's fetches through the host at all: the url is transformed and the
    // request is signed once, here, so a plugin cannot get either wrong by not
    // knowing about them. Signed AFTER the url is transformed, because a
    // signature covers the url that will actually be sent.
    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        val transport: Fetcher = fetcher
            ?: throw NotImplementedError("This player was built without an HTTP transport.", "fetch")

        // Through the pipeline, not straight at the transport.
        //
        // This signed the request and handed it over raw: a 404 came back as a
        // FetchResponse with a status on it and every caller decided for itself
        // what that meant, and the three fetch:* events the contract declares
        // were fired by nothing at all. Both are the same omission — the part
        // between "send" and "answer" did not exist here.
        return AuthFetch(
            fetcher = transport,
            auth = context.auth,
            onSignal = ::announceFetch,
        ).fetch(url, opts)
    }

    private fun announceFetch(signal: FetchSignal) {
        when (signal) {
            is FetchSignal.Start ->
                context.emit(CoreEvents.FetchStart, FetchStartPayload(url = signal.url))

            is FetchSignal.Retry -> context.emit(
                CoreEvents.FetchRetry,
                FetchRetryPayload(
                    url = signal.url,
                    attempt = signal.attempt.toDouble(),
                    reason = signal.reason,
                    delayMs = signal.delayMs.toDouble(),
                ),
            )

            is FetchSignal.Complete -> context.emit(
                CoreEvents.FetchComplete,
                FetchCompletePayload(
                    url = signal.url,
                    ok = signal.ok,
                    status = signal.status?.toDouble(),
                    // Not measured here. A duration wants a clock this method
                    // does not hold, and a fabricated zero would be worse than
                    // the field being absent.
                    durationMs = 0.0,
                ),
            )
        }
    }

    override fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel =
        throw NotImplementedError("This player was built without a realtime transport.", "websocket")

    // Falls back to the key so an untranslated string is visibly a key rather
    // than an empty label.
    //
    // A translator answering with the key it was given is a miss, and it is
    // reported the same way as having no translator at all: from the outside
    // the two are one thing, a string that never got translated.
    override fun t(namespacedKey: String, vars: Map<String, String>): String {
        val translated: String? = translator?.t(namespacedKey, vars)
        if (translated == null || translated == namespacedKey) {
            onMissingTranslation?.invoke(namespacedKey, vars)
        }
        return translated ?: namespacedKey
    }

    // The rest of the translator, reachable through the player.
    //
    // A plugin adds its strings and takes them away again when it leaves, and
    // it has the player, not the translator. Routing through here is also what
    // makes the port optional: every one of these is a no-op without a
    // translator injected, so a host that has its own i18n system does not have
    // to satisfy an interface it will never use.
    public open fun language(): String = translator?.language() ?: UNTRANSLATED

    public open suspend fun language(lang: String) {
        val outcome: BeforeDispatchResult<BeforeLanguagePayload> =
            context.dispatchBefore(CoreEvents.BeforeLanguage, BeforeLanguagePayload(lang = lang))
        if (outcome.prevented) {
            context.emit(
                CoreEvents.LanguagePrevented,
                LanguagePreventedPayload(reason = outcome.reason ?: LISTENER_PREVENTED),
            )
            return
        }

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

    // Every error in the library passes through here, which is why the fatal
    // channel hangs off it.
    //
    // CoreEvents.Fatal was declared, listed in the registry, and emitted by
    // NOTHING anywhere in the trio — so a consumer subscribing to `fatal` heard
    // nothing ever, and PlayState.ERROR was unreachable because the only thing
    // that would write it was a fatal that never arrived. The severity was on
    // the payload the whole time and nobody read it.
    override fun report(error: PlayerError) {
        logger.error("${error.code}: ${error.message}")

        val payload: PlayerErrorEvent = error.asEvent()

        // The severity's own channel, then the plugin-scoped one, exactly as the
        // reference does it.
        //
        // This logged and returned for anything short of FATAL, so every
        // warning a plugin raised reached the log and no listener at all — the
        // four channels below were declared and never emitted. A subtitle
        // reporting that it could not read a font is precisely the case: the
        // film keeps playing, so it is not fatal, and the viewer sees the wrong
        // typeface with nothing to explain it.
        when (error.severity) {
            Severity.INFO -> context.emit(CoreEvents.Info, payload)
            Severity.WARNING -> context.emit(CoreEvents.Warning, payload)
            Severity.ERROR -> context.emit(CoreEvents.Error, payload)
            Severity.FATAL -> Unit
        }

        if (error.scope.kind == ScopeKind.PLUGIN) {
            when (error.severity) {
                Severity.WARNING -> context.emit(CoreEvents.PluginWarning, payload)
                Severity.ERROR -> context.emit(CoreEvents.PluginError, payload)
                else -> Unit
            }
        }

        if (error.severity != Severity.FATAL) return

        // The state settles BEFORE the listener chain, exactly as the reference
        // does it: a fatal listener already observes ERROR rather than the
        // PLAYING it was a moment ago, and the flip cannot be skipped by a
        // listener that stops propagation.
        context.playState = PlayState.ERROR

        context.emit(CoreEvents.Fatal, payload)
    }
}

// One error, one payload. Written out twice — once for a playlist failure and
// once for the fatal channel — is two places for a field to be forgotten in, and
// the second copy is how a `suggestion` reaches one listener and not the other.
private fun PlayerError.asEvent(): PlayerErrorEvent = PlayerErrorEvent(
    code = code,
    message = message.orEmpty(),
    severity = severity,
    scope = scope,
    suggestion = suggestion,
    context = context,
)

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
