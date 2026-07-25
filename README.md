# nomercy-player-core-kmp

The shared engine behind the NoMercy native players. It holds the parts that have
nothing to do with a screen or a decoder: the event system, playback state, the
plugin runtime, and the ports that platform engines plug into.

This is the Kotlin Multiplatform sibling of
[`@nomercy-entertainment/nomercy-player-core`](https://www.npmjs.com/package/@nomercy-entertainment/nomercy-player-core).
The web package is the specification. Where the two disagree, the web package is
right and this one has a bug.

## Status

Scaffold. The build, the publishing pipeline, and the gates are in place and
proven; the engine itself is being written on top of them. Nothing is published
to Maven Central yet.

## Targets

| Target | Consumed by |
|---|---|
| `androidTarget` | the Android phone and TV app |
| `jvm` | the desktop client and the test harness |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | the iOS app, through `NoMercyPlayerCore.xcframework` |
| `tvosArm64`, `tvosSimulatorArm64` | the tvOS app, through the same xcframework |

## Building

You need JDK 21 and an Android SDK. Everything except linking Apple frameworks
builds on any host, including Windows and Linux, because Kotlin/Native compiles
klibs without Apple hardware.

```
./gradlew build
```

That runs the JVM and Android tests, detekt, and both API checks. Running the
Apple unit tests and linking the xcframework needs macOS:

```
./gradlew iosSimulatorArm64Test tvosSimulatorArm64Test
./gradlew assembleNoMercyPlayerCoreXCFramework
```

## Gates

Four things fail the build, and none of them have a baseline file to hide in.

**Public API.** Every public symbol is recorded in `api/`. Adding, removing or
changing one without updating those files fails `apiCheck`. Both the JVM surface
and the Kotlin/Native klib surface are covered, so an Apple-only change is caught
on a Linux runner. Run `./gradlew apiDump` when a change to the surface is
intended, and read the diff before committing it.

**Complexity.** `config/detekt/detekt.yml` sets thresholds below detekt's own
defaults. This is a library that people read to learn the plugin API, so every
function in it is documentation whether it was meant to be or not.

**Versions.** `KIT_VERSION` and `CONTRACT_VERSION` are generated from
`gradle.properties` at build time and never typed into Kotlin. A hand-maintained
version constant and the test asserting it drift together silently, which is
exactly how the web contract generator once claimed to describe a release that
had already been superseded.

**Tag and version agreement.** Publishing checks that the git tag matches
`VERSION_NAME` before it uploads anything.

## Publishing

Through CI only, triggered by a `v*` tag, from the macOS runner. A multiplatform
publication has to carry every declared target, and the Apple frameworks can only
be linked on Apple hardware. Publishing from anywhere else would ship an artifact
that quietly has no Apple variants in it.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
