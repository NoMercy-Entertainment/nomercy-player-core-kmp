# Changelog — tv.nomercy:nomercy-player-core-kmp

The published coordinate is `2.0.0-rc.1`. Everything below is unreleased work on top
of it, and the breaking entries are the ones a consumer has to act on: the KMP trio
is consumed by `nomercy-app-kmp` and by the acceptance consumers, which resolve the
published artifact rather than a composite build, so a signature that changes here
is a compile error there the moment the next version is published.

Signatures are read from the checked-in `api/` dumps rather than remembered, because
a Kotlin data class rewrites `copy`, `componentN` and its constructor whenever a
field is added — those are binary-breaking and source-safe, and they are not listed
individually. What is listed is what a consumer can actually name.

## [Unreleased]

### Breaking

- `SubtitleStyle` (`tv.nomercy.player.core.events`) was rewritten to the web's own
  nine fields and names, replacing six. `color` and `opacity` are gone as such and
  `fontSize` changed from `Double?` to `Int`. A consumer reading those three by name
  no longer compiles. Nothing in `nomercy-app-kmp` referenced them — its subtitle
  menu carries its own `TextTrackStyle` — so the migration cost today is zero, and
  the change is what makes the native style menu and the web's agree at all.
- `CueParser` became `CueParser<T>` and cue payloads are `Cue<T>`, matching the web's
  `ICueParser<T>`/`CueList<T>` which the port had flattened. The flat `CueEvent`
  remains for the subtitle/chapter crossing. Collapsing the generic away had cost
  enhanced-LRC word timing, sprite-VTT rectangles and WebVTT `align`/`line`/`size`
  cue settings — three formats losing their payload to one narrowing.
- `PlayerConfig` gained `hdrOnSdr`, `wakeLock` and `onOffline`. Source-compatible in
  Kotlin (all defaulted) and **not** source-compatible in Swift: a Kotlin default
  does not survive into the generated ObjC header, so every added field becomes a
  required argument at an Apple call site.

### Added

- Screen-aware quality: `SizeAbrConstraint` caps the ladder to the space the picture
  is drawn in, and `MediaBackend.surfaceSize` reports it. On desktop this stopped a
  ~740px pane pulling a 3840x1666 rendition.
- `HdrOnSdrFallback` and the HDR decision path, with a real per-platform display
  probe rather than a stub.
- `ActionSource.PLATFORM`, so a pause the platform asked for is distinguishable from
  one a viewer asked for.
- Built-in cue parsers (`lrc`, `vtt`, `sprite-vtt`) seeded on the registry at
  construction, and `PluginHost.resolveCueParser` so a plugin reaches the host's
  registry instead of keeping a private one.

### Fixed

- `StateController` recomputed its snapshot on fourteen events and `Duration` was not
  one of them, so a loaded, not-yet-playing item reported `duration = 0` to every
  chrome: no progress, no chapter markers, every scrub position mapping to the start.
  It corrected itself on the first play tick, which is why it survived.
- The same snapshot built thirteen fields and omitted `bufferState`, so the buffering
  spinner a chrome drives could not appear through any stall.
- `bufferState` was only ever cleared by `canplay`, a rule borrowed from a media
  element that re-fires it after every stall. libVLC announces it once per item, so a
  desktop stall never cleared — measured as `STALLED` for eighty seconds of a film
  playing at full rate. A position that keeps advancing now clears it.
- `BackendBridge` listened to ten of the twelve canonical backend events. `ERROR` was
  not one, so every engine failure — and the HDR-unplayable refusal on all three
  platforms — announced into nothing.
- `TimeController.checkItemEndingSoon` had no caller outside its own test, so the
  auto-advance hand-off window, the preload head start and the up-next card were idle
  on every platform.
- `ChapterController` was instantiated nowhere but its own test; `chapters(list)` set
  the track directly and bypassed it, so chapter gap-fill never ran and `Chapters` was
  never emitted at all.
- `MediaBackend.buffered()` is a walk from the playhead, not the furthest buffered
  end, and the walk is now the interface default so an engine reporting ranges cannot
  get it wrong. With ranges `[0,90]` and `[3500,3600]` and the playhead at 5, the old
  answer promised an hour of buffer across a hole.
- The engine's own play and pause were never bridged, so any stop the player did not
  cause — audio focus lost to a call, headphones pulled, an OS media key — reached no
  consumer, and the chrome, the notification and every other device on the account
  went on showing playing.
- Volume reached the engine as the raw slider position: `perceptualGain` existed
  nowhere, so the same slider was audibly louder on every native platform than on the
  web. A level arriving while muted also overwrote the level to return to, which is a
  bug the web had already fixed and the port reintroduced.
- `parseVtt` split on the literal `"\n\n"`. A CRLF file therefore arrived as one block
  whose body was the rest of the document, and — a second, independent defect in the
  same function — a separator line carrying a space or a tab merged two cues and
  swallowed the second.
- `HDR_UNPLAYABLE` is a declared code now that the vendored contract carries it.
