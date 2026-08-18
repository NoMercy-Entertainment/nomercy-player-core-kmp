/*
 * Copyright (c) 2026 NoMercy Entertainment. All rights reserved.
 */

package tv.nomercy.player.testing

import tv.nomercy.player.core.ports.AudioBackend
import tv.nomercy.player.core.ports.AudioDspGraph
import tv.nomercy.player.core.ports.CrossfadeCurve

/**
 * The audio counterpart of [FakeVideoBackend], so a music player can be built
 * in a test without an engine.
 *
 * Crossfade is recorded rather than performed: a test asks what the player
 * asked the engine to do, which is the part a consumer can get wrong. The DSP
 * graph stays null on purpose — [AudioBackend.audioGraph]'s own contract is
 * that absent means absent, and handing back a graph that accepts every call
 * and changes nothing would let an equaliser test pass while the control does
 * nothing.
 */
public open class FakeAudioBackend : FakeMediaBackend(), AudioBackend {

    /** Every crossfade the player asked for, in order. */
    public val crossfades: MutableList<Pair<Long, CrossfadeCurve>> = mutableListOf()

    /** Urls handed to the secondary voice, in order. */
    public val secondaryLoads: MutableList<String> = mutableListOf()

    /** Seek positions the secondary was primed at, in order. */
    public val secondaryPrimes: MutableList<Long> = mutableListOf()

    public var secondaryDisposals: Int = 0
        private set

    private var secondaryGain: Float = 1f

    /** Flip to false to test the path a backend without crossfade takes. */
    public var crossfadeSupported: Boolean = true

    override fun supportsCrossfade(): Boolean = crossfadeSupported

    override suspend fun loadSecondary(url: String) {
        secondaryLoads += url
    }

    override suspend fun primeSecondary(seekMs: Long) {
        secondaryPrimes += seekMs
    }

    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) {
        crossfades += durationMs to curve
    }

    override fun disposeSecondary() {
        secondaryDisposals++
    }

    override fun secondaryGain(): Float = secondaryGain

    override fun secondaryGain(value: Float) {
        secondaryGain = value
    }

    override fun audioGraph(): AudioDspGraph? = null
}
