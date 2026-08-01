// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.nomercy.player.core.events.PlaybackMetrics
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.defaultClock

// The counters that have a field on PlaybackMetrics.
//
// An enum rather than the web's free-form string keys, because these six are
// what a support ticket asks about and a typo in "droppedFrames" should not
// produce a second counter nobody reads. Anything else a backend or plugin
// wants to count still goes in by name — see recordMetric(String, Double).
public enum class Metric(public val key: String) {
    DROPPED_FRAMES("droppedFrames"),
    TOTAL_FRAMES("totalFrames"),
    BUFFERING_EVENTS("bufferingEvents"),
    BUFFERING_SECONDS("bufferingSeconds"),
    STARTUP_SECONDS("startupSeconds"),
    BITRATE("bitrate"),
    ;

    public companion object {
        public fun byKey(key: String): Metric? = entries.firstOrNull { it.key == key }
    }
}

// What playback cost, while it is still costing it.
//
// The numbers here cannot be reconstructed after the fact: how long the first
// frame took, how many times it stalled, how much of the session was spent
// waiting. A support ticket that arrives a day later is asking about a session
// that is over, so the player has to have been counting.
public class MetricsController(private val clock: Clock = defaultClock()) {

    private var counters: PlaybackMetrics = PlaybackMetrics()
    private val custom: MutableMap<String, Double> = mutableMapOf()
    private var startedAt: Long? = null

    // Wall-clock milliseconds, through the injected clock.
    //
    // Everything that stamps or expires reads this rather than the system
    // clock, which is what lets a test decide what time it is and a host on a
    // synchronised fleet hand over a shared one. Two devices in a Connect
    // session comparing timestamps need to agree about now.
    public fun now(): Long = clock.now()

    // The running snapshot, with the session duration filled in at read time.
    //
    // Derived rather than stored: a duration that only advanced when something
    // else happened to write a metric would stand still during the exact stretch
    // a stalled player is being asked about.
    public fun metrics(): PlaybackMetrics = counters.copy(
        sessionDurationMs = startedAt?.let { now() - it } ?: 0L,
        custom = custom.toMap(),
    )

    public fun recordMetric(metric: Metric, value: Double) {
        counters = when (metric) {
            Metric.DROPPED_FRAMES -> counters.copy(droppedFrames = value)
            Metric.TOTAL_FRAMES -> counters.copy(totalFrames = value)
            Metric.BUFFERING_EVENTS -> counters.copy(bufferingEvents = value)
            Metric.BUFFERING_SECONDS -> counters.copy(bufferingSeconds = value)
            Metric.STARTUP_SECONDS -> counters.copy(startupSeconds = value)
            Metric.BITRATE -> counters.copy(bitrate = value)
        }
    }

    // By name, for the counters this library does not know about: a plugin
    // counting lyric fetches, a backend counting decoder resets. A name that
    // matches a known metric lands on its field rather than beside it, so a
    // backend written against the web's string API does not produce two
    // droppedFrames that disagree.
    public fun recordMetric(name: String, value: Double) {
        val known: Metric? = Metric.byKey(name)
        if (known != null) recordMetric(known, value) else custom[name] = value
    }

    // Called when an item starts. The session is per item, not per player: a
    // duration spanning a whole queue would answer "how long has this been
    // playing" with how long the app has been open.
    public fun startSession() {
        startedAt = now()
        counters = PlaybackMetrics()
        custom.clear()
    }

    public fun endSession() {
        startedAt = null
    }

    // Hand a snapshot to a sink every [intervalMs], until stopped.
    //
    // Without this the counters are only readable by a consumer that thought to
    // poll them, which is the one thing a support ticket cannot do after the
    // fact. Zero turns sampling off — a host shipping its own telemetry loop
    // should not be paying for a second one.
    public fun startSampling(scope: CoroutineScope, intervalMs: Long, sink: (PlaybackMetrics) -> Unit) {
        stopSampling()
        if (intervalMs <= 0L) return

        sampler = scope.launch {
            while (isActive) {
                delay(intervalMs)
                sink(metrics())
            }
        }
    }

    public fun stopSampling() {
        sampler?.cancel()
        sampler = null
    }

    private var sampler: Job? = null
}
