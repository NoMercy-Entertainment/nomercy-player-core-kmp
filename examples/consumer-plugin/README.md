# consumer-plugin

The anti-hollow acceptance for P31: a real plugin, authored and tested by a
Gradle build that has never seen this library's source tree — only its
published (or, for local co-dev, composite-substituted) jars.

`WatchCountPlugin` counts `play` events using nothing but
`tv.nomercy.player.core.*`'s public surface. Its test uses only the shipped
`tv.nomercy:nomercy-player-core-kmp-testing-jvm` fakes (`FakePlayer`,
`testPlugin`). `InspectorDemo` attaches the shipped `PlayerInspector` and
prints the live event stream.

```bash
./gradlew test   # WatchCountPluginTest, against the shipped testing kit
./gradlew run    # InspectorDemo, prints the captured event order
```
