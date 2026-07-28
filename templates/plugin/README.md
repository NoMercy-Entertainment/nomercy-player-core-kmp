# A plugin, to copy

This directory is a working plugin. It compiles, it has tests, and the advisory
rule pack runs over it. Copy it and replace the body.

```bash
cp -r templates/plugin ../my-plugin
```

Then rename three things and you have your own:

- the package, `tv.nomercy.player.template` to yours
- `HelloPlugin`, `HelloEvents`, `HelloOptions` to your names
- the manifest `id`, from `hello` to yours

The id is the one that matters at runtime. It namespaces your storage keys, your
log lines, your translations and every event you emit, so two plugins that both
store `enabled` never see each other's value.

## What to depend on

Inside this repository the template depends on the library by project path.
Outside it, those two lines are coordinates:

```kotlin
commonMain.dependencies {
    implementation("tv.nomercy:nomercy-player-core-kmp:<version>")
}
commonTest.dependencies {
    implementation("tv.nomercy:nomercy-player-core-kmp-testing:<version>")
}
```

The second is the testing kit. It ships the stand-in player, the fake ports and
the harness the test in here uses.

## The blessed path

Four things separate a plugin from a listener, and the template does all four.

**A manifest as the companion.** The registry reads it before any of your code
runs, so a plugin whose dependency is missing fails before a half-initialised
one exists. Putting it on the companion keeps the id in one place, where the
class cannot disagree with it.

**Your own event registry.** Declare an `EventKey` per event rather than
emitting strings. A consumer subscribes with `player.on(HelloEvents.Greeted)`
and gets the payload type with it, and a rename becomes a compile error instead
of a silent miss. Emit a payload type rather than a bare `String`: widening one
later is a breaking change for everybody listening.

**`this.on` and `this.emit`, never `player.on`.** The base class versions
unsubscribe when your plugin goes away and stop firing while it is disabled, and
`this.emit` sends under `plugin:<id>:` where a plugin listening for your event
will find it. The same goes for `this.timeout`, `this.interval`, `this.listen`,
`this.fetch`, `this.websocket` and `this.storage`. Everything reached through
the base is cleaned up with the plugin; everything reached around it is not.

**`t()` for anything a person reads.** It looks under `plugin.<id>.<key>`, so
your strings arrive in the viewer's language and cannot shadow a core one.

## Why the rules are advisory

`detekt.yml` turns on a small pack that watches for the raw versions of the
calls above. Every rule names the sanctioned call at the site of the raw one,
and none of them block anything: `@Suppress("NoRawPlayerBus")` silences one and
nothing changes at runtime.

They are there for the half hour between writing `player.on(...)` inside a
plugin and finding out at teardown that it was never removed. Try it: give
`HelloPlugin` a `private val host: PluginHost` constructor parameter, change
`on(CoreEvents.Play)` to `host.on(CoreEvents.Play)`, and run

```bash
./gradlew :templates:plugin:detekt
```

You get the file, the line and the call to use instead.

## Testing it

```bash
./gradlew :templates:plugin:jvmTest
```

`testPlugin` registers your plugin through the real registry, hands you the
player and the plugin, tears it down, and asserts the event bus came back to
where it started. The leak check is free, which is what makes it a check that
actually runs: an author writing an ordinary behaviour test gets it whether or
not they were thinking about teardown.

The plugin is registered on a `FakePlayer` — a real emitter, a real registry, a
real state holder, and no engine. Pair it with `FakeMediaBackend` when what you
are testing cares about what an engine said.

## Where to read more

The plugin base class in `src/commonMain/kotlin/tv/nomercy/player/core/plugin/Plugin.kt`
is the full surface, and its comments say what each helper cleans up.
