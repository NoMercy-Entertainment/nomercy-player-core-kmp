# detekt-player

Six pieces of advice for anyone writing a plugin against the NoMercy player.

Every rule here is advisory. None of them prevents anything at runtime, none of
them seals a class or makes a member final, and every one can be silenced with
`@Suppress` at the site. That is deliberate: the library steers you toward the
sanctioned surface at the point of writing, and if you mean it, you say so and
carry on. A rule pack that could not be silenced would be a wall, and this
project decided not to build walls.

## What each rule is about

| Rule | Fires when | Why it matters |
|---|---|---|
| `NoRawPlayerBus` | `this.player.on/once/off/emit(...)` inside a `Plugin` subclass | The raw bus never unsubscribes at teardown, and an emit lands on the global channel instead of `plugin:<id>:` where another plugin would look for it |
| `NoRawTimersInPlugin` | `launch`, `delay`, `Timer` inside a plugin, unscoped | A coroutine a plugin starts itself outlives the plugin. `timeout`, `interval`, `frame` and `listen` are cancelled for you |
| `NoRawFetchInPlugin` | A plugin builds its own HTTP client | The host's `fetch` already carries the token, the base url and the refresh-and-retry; a hand-rolled client gets a 401 it then has to handle |
| `PluginManifestRequired` | A concrete `Plugin` subclass with no `manifest` | The manifest's id is what storage keys, event names, error scopes and `requires` all hang off |
| `NoUncheckedCast` | `@Suppress("UNCHECKED_CAST")` | The cast fails at the call site, not at the suppression, and the stack trace blames the bus. A typed `EventKey` carries the payload type so the cast is not needed |
| `NoSingleLetterIdent` | A one-letter name outside the counter and coordinate conventions | A one-letter name has to be held in your head |

All six only look inside classes that extend `Plugin`, except `NoUncheckedCast`
and `NoSingleLetterIdent`, which are about reading the code rather than about the
plugin boundary. Core's own controllers reach the bus directly because they are
the bus, and the rules stay quiet there.

## Using it

```kotlin
dependencies {
    detektPlugins("tv.nomercy.player:detekt-player:<version>")
}
```

```yaml
player:
  active: true
  NoRawPlayerBus:
    active: true
```

## Silencing one

```kotlin
class Lyrics : Plugin<LyricsOptions>() {
    // The raw bus is what we want here: this listener has to outlive the plugin
    // because <reason>.
    @Suppress("NoRawPlayerBus")
    override fun use() {
        player.on("play") { }
    }
}
```

Write the reason. The rule exists to make you state it once, not to stop you.

## Running it against this library

The pack runs against the player library itself, where it is a hard failure
rather than advice — the library is the example everyone else reads. It has
already earned its keep: switching it on flagged the two unchecked casts in the
codebase, and both now carry the argument for why they are there.
