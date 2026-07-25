package tv.nomercy.player.core

import kotlin.test.Test
import kotlin.test.assertTrue

private val SEMVER = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")

/**
 * The web trio guards `KIT_VERSION` with an equality test because its value is
 * hand-maintainable and once went stale at `2.0.0-beta.1`. Here the value cannot
 * go stale: it is generated from gradle.properties on every build, so there is
 * nothing to compare it against that would not be comparing it to itself.
 *
 * What is worth asserting is that the generation ran at all and produced a usable
 * value. That also proves the whole rail end to end — gradle.properties through
 * `generateKitVersion` into commonMain, compiled and executed on this target.
 */
class KitVersionTest {

    @Test
    fun kitVersionIsGeneratedAsSemver() {
        assertTrue(
            SEMVER.matches(KIT_VERSION),
            "KIT_VERSION is '$KIT_VERSION', which is not a semver",
        )
    }

    @Test
    fun contractVersionIsGeneratedAsSemver() {
        assertTrue(
            SEMVER.matches(CONTRACT_VERSION),
            "CONTRACT_VERSION is '$CONTRACT_VERSION', which is not a semver",
        )
    }
}
