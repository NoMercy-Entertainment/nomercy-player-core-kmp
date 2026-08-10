// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

// ACTION_AUDIO_BECOMING_NOISY onto [AudioFocusPlugin.handleBecomingNoisy] —
// the wiring the plugin itself cannot do, because it has no Context and
// building one is a platform's job, not core's.
//
// A registered receiver rather than a manifest one: this fires only while
// something in this process is actually playing, which is exactly the
// lifetime a foreground service or an Activity already manages, and a
// manifest receiver would wake the whole app for a headphone unplug with
// nothing playing to pause.
public class BecomingNoisyReceiver(
    private val plugin: AudioFocusPlugin,
) : BroadcastReceiver() {

    private var registeredIn: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
        plugin.handleBecomingNoisy()
    }

    public fun register(context: Context) {
        unregister()
        val appContext: Context = context.applicationContext
        appContext.registerReceiver(this, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        registeredIn = appContext
    }

    public fun unregister() {
        val context: Context = registeredIn ?: return
        registeredIn = null
        context.unregisterReceiver(this)
    }
}
